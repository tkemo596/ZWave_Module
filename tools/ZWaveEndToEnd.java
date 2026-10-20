package com.arringtonautomation.ignition.zwave.zwjs;

import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Drives ZwjsClient + ZWaveModel against tools/mock_zwjs_server.py exactly the way ZWaveDevice
 * does - handshake, schema, start_listening, event replay, writes - minus the Milo node tree.
 *
 * <pre>
 * python tools/mock_zwjs_server.py tools/fixtures/start_listening_state.json 3999 --pad 400 &amp;
 * java -cp out com.arringtonautomation.ignition.zwave.zwjs.ZWaveEndToEnd ws://127.0.0.1:3999
 * </pre>
 */
public final class ZWaveEndToEnd {

  private static int passed;
  private static final List<String> failures = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    String base = args.length > 0 ? args[0] : "ws://127.0.0.1:3999";

    ZWaveModel model = new ZWaveModel();
    ConcurrentLinkedQueue<Map<String, Object>> early = new ConcurrentLinkedQueue<>();
    boolean[] listening = {false};
    AtomicInteger events = new AtomicInteger();
    CountDownLatch closed = new CountDownLatch(1);
    CountDownLatch switchReported = new CountDownLatch(1);
    String current = new ValueId(37, 0, "currentValue", null).key();

    ZwjsClient client =
        new ZwjsClient(
            URI.create(base),
            5000,
            new ZwjsClient.Listener() {
              @Override
              public void onEvent(Map<String, Object> event) {
                events.incrementAndGet();
                if (!listening[0]) {
                  early.add(event);
                  return;
                }
                model.applyEvent(event);
                ZValue v = model.value(5, current);
                if (v != null && Boolean.TRUE.equals(v.value())) {
                  switchReported.countDown();
                }
              }

              @Override
              public void onClosed(String reason) {
                closed.countDown();
              }
            });

    // Handshake, as ZWaveDevice.openConnection does it.
    long t0 = System.currentTimeMillis();
    Map<String, Object> version = client.connect();
    check("connect: version message", "15.0.0-mock".equals(version.get("driverVersion")));
    int schema = ZwjsClient.chooseSchema(version, 0);
    check("connect: newest schema chosen", schema == 42);
    client.request("set_api_schema", Map.of("schemaVersion", schema));

    Map<String, Object> result = client.request("start_listening", null, 60_000);
    Map<String, Object> state = Json.obj(result, "state");
    check("start_listening: state returned", state != null);
    model.setServerInfo(version, schema);
    model.loadState(state);
    listening[0] = true;
    Map<String, Object> e;
    while ((e = early.poll()) != null) {
      model.applyEvent(e);
    }
    long elapsed = System.currentTimeMillis() - t0;
    int nodes = model.nodeCount();
    check("start_listening: large multi-frame state parsed (" + nodes + " nodes, " + elapsed + " ms)",
        nodes > 4);
    int tags = model.buildTags(new ZWaveModel.Options(false,
        ZWaveModel.Options.parseExclusions(ZWaveModel.DEFAULT_EXCLUDED_CCS), true, true)).size();
    check("tree: built from live state (" + tags + " tags)", tags > 100);

    // Write a switch and wait for the device-reported currentValue, as a real one behaves.
    ZValue target = model.value(5, new ValueId(37, 0, "targetValue", null).key());
    Map<String, Object> setArgs = new LinkedHashMap<>();
    setArgs.put("nodeId", 5);
    setArgs.put("valueId", target.id.toJson());
    setArgs.put("value", ValueCodec.toZwave(target, "on"));
    Map<String, Object> setResult = client.request("node.set_value", setArgs);
    check("write: accepted", ValueCodec.setValueFailure(setResult) == null, String.valueOf(setResult));
    check("write: currentValue event arrived", switchReported.await(3, TimeUnit.SECONDS));

    // Dimmer out of range -> server-side validation surfaces as a failure reason.
    ZValue dim = model.value(9, new ValueId(38, 0, "targetValue", null).key());
    Map<String, Object> dimArgs = new LinkedHashMap<>();
    dimArgs.put("nodeId", 9);
    dimArgs.put("valueId", dim.id.toJson());
    dimArgs.put("value", ValueCodec.toZwave(dim, 150.0));
    String failure = ValueCodec.setValueFailure(client.request("node.set_value", dimArgs));
    check("write: out of range rejected", failure != null && failure.contains("out of range"), failure);

    dimArgs.put("value", ValueCodec.toZwave(dim, 50.0));
    check("write: Double 50.0 sent as integer and accepted",
        ValueCodec.setValueFailure(client.request("node.set_value", dimArgs)) == null);

    // Commands.
    client.request("node.refresh_values", Map.of("nodeId", 5));
    check("command: refresh_values", true);
    try {
      client.request("node.set_value", Map.of("nodeId", 77, "valueId", target.id.toJson(), "value", true));
      check("error: unknown node throws", false);
    } catch (ZwjsException ex) {
      check("error: unknown node code", "node_not_found".equals(ex.getErrorCode()), ex.getMessage());
    }
    try {
      client.request("no.such.command", null);
      check("error: unknown command throws", false);
    } catch (ZwjsException ex) {
      check("error: unknown command code", "unknown_command".equals(ex.getErrorCode()));
    }

    // Concurrent writes must not trip java.net.http's one-send-at-a-time rule.
    List<Thread> threads = new ArrayList<>();
    AtomicInteger ok = new AtomicInteger();
    for (int i = 0; i < 20; i++) {
      Thread t = new Thread(() -> {
        try {
          client.request("node.refresh_values", Map.of("nodeId", 5));
          ok.incrementAndGet();
        } catch (ZwjsException ex) {
          System.err.println(ex.getMessage());
        }
      });
      threads.add(t);
      t.start();
    }
    for (Thread t : threads) {
      t.join();
    }
    check("concurrency: 20 parallel requests", ok.get() == 20, ok + "/20");

    // Liveness.
    Thread.sleep(500);
    long sinceBefore = client.millisSinceLastPong();
    client.ping();
    Thread.sleep(200);
    long sinceAfter = client.millisSinceLastPong();
    check("ping: pong received", sinceAfter < sinceBefore && sinceAfter <= 200,
        sinceBefore + " -> " + sinceAfter);

    // Server-pushed sensor updates keep flowing.
    Object before = model.value(7, new ValueId(49, 1, "Air temperature", null).key()).value();
    Thread.sleep(3500);
    Object after = model.value(7, new ValueId(49, 1, "Air temperature", null).key()).value();
    check("events: sensor update pushed and applied", events.get() > 1 && after != null, before + " -> " + after);

    // Server drops us: listener told, pending and future requests fail fast.
    try {
      client.request("test.close", null, 2000);
    } catch (ZwjsException expected) {
      // the close races the reply; either is fine
    }
    check("close: listener notified", closed.await(3, TimeUnit.SECONDS));
    check("close: client reports closed", !client.isOpen());
    try {
      client.request("node.ping", Map.of("nodeId", 5));
      check("close: request after close fails", false);
    } catch (ZwjsException ex) {
      check("close: request after close fails", true);
    }

    // Wrong server: socket opens but no version message.
    ZwjsClient silent = new ZwjsClient(URI.create(base + "/silent"), 1500, quiet());
    try {
      silent.connect();
      check("silent: rejected", false);
    } catch (ZwjsException ex) {
      check("silent: rejected with a useful message", ex.getMessage().contains("no version message"), ex.getMessage());
    }

    // Nothing listening.
    ZwjsClient nobody = new ZwjsClient(URI.create("ws://127.0.0.1:1"), 1500, quiet());
    long start = System.currentTimeMillis();
    try {
      nobody.connect();
      check("refused: rejected", false);
    } catch (ZwjsException ex) {
      check("refused: fails fast (" + (System.currentTimeMillis() - start) + " ms)",
          System.currentTimeMillis() - start < 2000, ex.getMessage());
      check("refused: message says why", ex.getMessage().contains("connection refused"), ex.getMessage());
    }

    System.out.println();
    System.out.println(passed + " checks passed, " + failures.size() + " failed");
    for (String f : failures) {
      System.out.println("  FAIL " + f);
    }
    System.exit(failures.isEmpty() ? 0 : 1);
  }

  private static ZwjsClient.Listener quiet() {
    return new ZwjsClient.Listener() {
      @Override
      public void onEvent(Map<String, Object> event) {}

      @Override
      public void onClosed(String reason) {}
    };
  }

  private static void check(String name, boolean ok) {
    check(name, ok, null);
  }

  private static void check(String name, boolean ok, String detail) {
    System.out.println((ok ? "  ok   " : "  FAIL ") + name + (ok || detail == null ? "" : " -> " + detail));
    if (ok) {
      passed++;
    } else {
      failures.add(name + (detail == null ? "" : " -> " + detail));
    }
  }
}
