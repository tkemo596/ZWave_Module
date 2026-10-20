package com.arringtonautomation.ignition.zwave.zwjs;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dependency-free checks for everything in the zwjs package. No Ignition, no Milo, no network.
 *
 * <pre>
 * javac -d out gateway/src/main/java/com/arringtonautomation/ignition/zwave/zwjs/*.java tools/ZWaveSelfCheck.java
 * java -cp out com.arringtonautomation.ignition.zwave.zwjs.ZWaveSelfCheck tools/fixtures/start_listening_state.json
 * </pre>
 */
public final class ZWaveSelfCheck {

  private static int passed;
  private static final List<String> failures = new ArrayList<>();

  public static void main(String[] args) throws Exception {
    Path fixture = Path.of(args.length > 0 ? args[0] : "tools/fixtures/start_listening_state.json");
    String stateJson = Files.readString(fixture, StandardCharsets.UTF_8);

    json();
    valueIds();
    codec();
    results();
    model(stateJson);
    events(stateJson);

    System.out.println();
    System.out.println(passed + " checks passed, " + failures.size() + " failed");
    for (String f : failures) {
      System.out.println("  FAIL " + f);
    }
    System.exit(failures.isEmpty() ? 0 : 1);
  }

  // ------------------------------------------------------------------ JSON

  private static void json() {
    Map<String, Object> m =
        Json.parseObject(
            " { \"a\" : [1, -2, 3.5, 1e3, true, false, null], \"s\": \"q\\\"\\\\\\n\\u00b0F\","
                + " \"o\": {} , \"big\": 12345678901234567890 } ");
    List<Object> a = Json.list(m, "a");
    check("json: long", Long.valueOf(1).equals(a.get(0)));
    check("json: negative long", Long.valueOf(-2).equals(a.get(1)));
    check("json: double", Double.valueOf(3.5).equals(a.get(2)));
    check("json: exponent is double", Double.valueOf(1000).equals(a.get(3)));
    check("json: booleans", Boolean.TRUE.equals(a.get(4)) && Boolean.FALSE.equals(a.get(5)));
    check("json: null element", a.get(6) == null && a.size() == 7);
    check("json: escapes and \\u", "q\"\\\n°F".equals(m.get("s")));
    check("json: empty object", Json.obj(m, "o") != null && Json.obj(m, "o").isEmpty());
    check("json: overflowing integer becomes double", m.get("big") instanceof Double);

    check("json: trailing junk rejected", throwsIae(() -> Json.parse("{} x")));
    check("json: unterminated rejected", throwsIae(() -> Json.parse("{\"a\": [1, 2")));
    check("json: bare word rejected", throwsIae(() -> Json.parse("nul")));

    Map<String, Object> out = new LinkedHashMap<>();
    out.put("n", 50.0);
    out.put("f", 72.5);
    out.put("nan", Double.NaN);
    out.put("l", List.of(1, "x"));
    out.put("s", "a\"b\u0001");
    String written = Json.write(out);
    check(
        "json: write",
        written.equals("{\"n\":50,\"f\":72.5,\"nan\":null,\"l\":[1,\"x\"],\"s\":\"a\\\"b\\u0001\"}"),
        written);
    check("json: round trip", Json.write(Json.parse(written)).equals(written));
  }

  // -------------------------------------------------------------- value ids

  private static void valueIds() {
    ValueId numeric = new ValueId(112, 0, 3L, null);
    ValueId text = new ValueId(112, 0, "3", null);
    check("valueId: 3 and \"3\" are different values", !numeric.equals(text));
    check("valueId: Double 3.0 normalises to Long 3", new ValueId(112, 0, 3.0, null).equals(numeric));

    Map<String, Object> js = numeric.toJson();
    check("valueId: propertyKey omitted when absent", !js.containsKey("propertyKey"));
    check("valueId: numeric property stays numeric", js.get("property") instanceof Long);

    ValueId meter = ValueId.fromJson(Json.parseObject(
        "{\"commandClass\":50,\"endpoint\":0,\"property\":\"value\",\"propertyKey\":65537}"));
    check("valueId: numeric propertyKey kept", Long.valueOf(65537).equals(meter.toJson().get("propertyKey")));
    check(
        "valueId: written exactly as Z-Wave JS expects",
        Json.write(meter.toJson())
            .equals("{\"commandClass\":50,\"endpoint\":0,\"property\":\"value\",\"propertyKey\":65537}"));
  }

  // ----------------------------------------------------------------- codec

  private static void codec() {
    ZValue num = valueOfType("number");
    check("codec: whole Double -> Long", Long.valueOf(50).equals(ValueCodec.toZwave(num, 50.0)));
    check("codec: fraction stays Double", Double.valueOf(72.5).equals(ValueCodec.toZwave(num, 72.5)));
    check("codec: numeric string", Long.valueOf(7).equals(ValueCodec.toZwave(num, " 7 ")));
    check("codec: boolean to number", Long.valueOf(1).equals(ValueCodec.toZwave(num, true)));
    check("codec: junk number rejected", throwsIae(() -> ValueCodec.toZwave(num, "warm")));
    check("codec: NaN rejected", throwsIae(() -> ValueCodec.toZwave(num, Double.NaN)));
    check("codec: null rejected", throwsIae(() -> ValueCodec.toZwave(num, null)));

    ZValue bool = valueOfType("boolean");
    check("codec: bool from 'on'", Boolean.TRUE.equals(ValueCodec.toZwave(bool, "on")));
    check("codec: bool from 0", Boolean.FALSE.equals(ValueCodec.toZwave(bool, 0)));
    check("codec: bad bool rejected", throwsIae(() -> ValueCodec.toZwave(bool, "maybe")));

    ZValue dur = valueOfType("duration");
    Object parsed = ValueCodec.toZwave(dur, "{\"value\":2,\"unit\":\"seconds\"}");
    check("codec: JSON text becomes an object", parsed instanceof Map<?, ?> p && "seconds".equals(p.get("unit")));
    check("codec: non-JSON text passes through", "default".equals(ValueCodec.toZwave(dur, "default")));

    check("codec: number to UA double", Double.valueOf(40).equals(ValueCodec.toUa(ZValue.UaKind.NUMBER, 40L)));
    check("codec: unparseable number to UA is null", ValueCodec.toUa(ZValue.UaKind.NUMBER, "unknown") == null);
    check(
        "codec: object to UA JSON",
        "{\"value\":2,\"unit\":\"seconds\"}"
            .equals(ValueCodec.toUa(ZValue.UaKind.JSON, Json.parse("{\"value\":2,\"unit\":\"seconds\"}"))));
    check("codec: array to UA JSON", "[\"1.30\"]".equals(ValueCodec.toUa(ZValue.UaKind.JSON, List.of("1.30"))));
  }

  // ---------------------------------------------------------------- results

  private static void results() throws Exception {
    check("result: legacy success", ValueCodec.setValueFailure(Json.parseObject("{\"success\":true}")) == null);
    check("result: legacy failure", ValueCodec.setValueFailure(Json.parseObject("{\"success\":false}")) != null);
    check("result: status 255", ValueCodec.setValueFailure(Json.parseObject("{\"result\":{\"status\":255}}")) == null);
    check("result: status 254 unsupervised", ValueCodec.setValueFailure(Json.parseObject("{\"result\":{\"status\":254}}")) == null);
    check("result: status 1 working is success", ValueCodec.setValueFailure(Json.parseObject("{\"result\":{\"status\":1}}")) == null);
    String fail = ValueCodec.setValueFailure(Json.parseObject("{\"result\":{\"status\":5,\"message\":\"out of range\"}}"));
    check("result: status 5 invalid value with message", fail != null && fail.contains("invalid value") && fail.contains("out of range"), fail);
    check("result: empty result is success", ValueCodec.setValueFailure(new LinkedHashMap<>()) == null);

    Map<String, Object> ok = ZwjsClient.checkResult("x", Json.parseObject(
        "{\"type\":\"result\",\"messageId\":\"1\",\"success\":true,\"result\":{\"a\":1}}"));
    check("client: success unwraps result", Long.valueOf(1).equals(ok.get("a")));
    try {
      ZwjsClient.checkResult("node.set_value", Json.parseObject(
          "{\"type\":\"result\",\"messageId\":\"2\",\"success\":false,\"errorCode\":\"node_not_found\"}"));
      check("client: failure throws", false);
    } catch (ZwjsException e) {
      check("client: failure throws with code", "node_not_found".equals(e.getErrorCode()));
    }

    // Windows JDK reports a refused connect as a bare ClosedChannelException (seen on the gateway).
    String refused = ZwjsClient.connectFailure(new java.util.concurrent.ExecutionException(
        new java.io.IOException(new java.nio.channels.ClosedChannelException())));
    check("connect: ClosedChannelException explained", refused.contains("connection refused")
        && refused.contains("WS Server"), refused);
    check("connect: ConnectException explained", ZwjsClient.connectFailure(
        new java.net.ConnectException()).contains("connection refused"));
    check("connect: other causes keep their message", "certificate rejected".equals(
        ZwjsClient.connectFailure(new java.io.IOException(new RuntimeException("certificate rejected")))));

    Map<String, Object> version = Json.parseObject("{\"minSchemaVersion\":0,\"maxSchemaVersion\":42}");
    check("schema: 0 means newest", ZwjsClient.chooseSchema(version, 0) == 42);
    check("schema: pin honoured", ZwjsClient.chooseSchema(version, 30) == 30);
    check("schema: pin above range clamps", ZwjsClient.chooseSchema(version, 99) == 42);
    check("schema: pin below range clamps",
        ZwjsClient.chooseSchema(Json.parseObject("{\"minSchemaVersion\":5,\"maxSchemaVersion\":9}"), 2) == 5);
  }

  // ----------------------------------------------------------------- model

  private static ZWaveModel loaded(String stateJson) {
    ZWaveModel model = new ZWaveModel();
    model.loadState(Json.parseObject(stateJson));
    return model;
  }

  private static ZWaveModel.Options defaults() {
    return new ZWaveModel.Options(
        false, ZWaveModel.Options.parseExclusions(ZWaveModel.DEFAULT_EXCLUDED_CCS), true, true);
  }

  private static Map<String, TagSpec> byPath(List<TagSpec> specs) {
    Map<String, TagSpec> m = new LinkedHashMap<>();
    for (TagSpec s : specs) {
      if (m.put(s.path(), s) != null) {
        failures.add("duplicate path " + s.path());
      }
    }
    return m;
  }

  private static void model(String stateJson) {
    ZWaveModel model = loaded(stateJson);
    check("model: four nodes", model.nodeCount() == 4);
    check("model: controller node id", Long.valueOf(1).equals(model.serverInfo("ControllerNodeId", true)));
    check("model: home id formatted", "0xdeadbeef".equals(model.serverInfo("HomeId", true)));

    Map<String, TagSpec> tags = byPath(model.buildTags(defaults()));

    check("tree: binary switch currentValue", tags.containsKey("Node_005.Binary_Switch.currentValue"));
    TagSpec target = tags.get("Node_005.Binary_Switch.targetValue");
    check("tree: targetValue writable", target != null && target.writable());
    check("tree: currentValue read-only", !tags.get("Node_005.Binary_Switch.currentValue").writable());
    check("tree: boolean kind", target != null && target.uaKind() == ZValue.UaKind.BOOLEAN);
    check("tree: Configuration excluded by default",
        tags.keySet().stream().noneMatch(p -> p.contains("Configuration")));
    check("tree: Version excluded by default",
        tags.keySet().stream().noneMatch(p -> p.contains(".Version.")));

    check("tree: endpoint folder", tags.containsKey("Node_007.EP1.Multilevel_Sensor.Air_temperature"));
    TagSpec temp = tags.get("Node_007.EP1.Multilevel_Sensor.Air_temperature");
    check("tree: unit in description", temp != null && temp.description().contains("[°F]"), temp == null ? "" : temp.description());
    check("tree: property key nests", tags.containsKey("Node_007.Notification.Home_Security.Motion_sensor_status"));
    check("tree: states in description",
        tags.get("Node_007.Notification.Home_Security.Motion_sensor_status").description().contains("Motion detection"));
    check("tree: meter key name used", tags.containsKey("Node_007.Meter.value.Electric_kWh_Consumed"));
    check("tree: duration is JSON", tags.get("Node_009.Multilevel_Switch.duration").uaKind() == ZValue.UaKind.JSON);

    check("tree: sanitised collision keeps the first name", tags.containsKey("Node_009.Multilevel_Switch.Up"));
    check("tree: sanitised collision suffixed", tags.containsKey("Node_009.Multilevel_Switch.Up_2"));

    check("tree: _Info status", tags.containsKey("Node_009._Info.Status"));
    check("tree: _Info RefreshValues writable", tags.get("Node_009._Info.RefreshValues").writable());
    check("tree: _Server tags", tags.containsKey("_Server.Connected") && tags.containsKey("_Server.HomeId"));
    check("tree: node info values",
        "Dead".equals(model.node(9).info("Status")) && "Asleep".equals(model.node(7).info("Status"))
            && "ZEN71".equals(model.node(5).info("Product")));

    // Every leaf name is a legal Ignition tag name, and no path segment is empty.
    boolean clean = tags.keySet().stream().allMatch(p ->
        java.util.Arrays.stream(p.split("\\.")).allMatch(s -> s.matches("[A-Za-z0-9_]+")));
    check("tree: every segment sanitised", clean);

    // A leaf never sits at a path that is also a folder.
    Set<String> folders = tags.keySet().stream()
        .flatMap(p -> {
          List<String> pre = new ArrayList<>();
          for (int i = p.indexOf('.'); i >= 0; i = p.indexOf('.', i + 1)) {
            pre.add(p.substring(0, i));
          }
          return pre.stream();
        })
        .collect(Collectors.toSet());
    check("tree: no tag/folder clash", tags.keySet().stream().noneMatch(folders::contains));

    // Options.
    Map<String, TagSpec> named = byPath(model.buildTags(new ZWaveModel.Options(
        true, ZWaveModel.Options.parseExclusions(ZWaveModel.DEFAULT_EXCLUDED_CCS), true, true)));
    check("options: named folders", named.containsKey("Kitchen_Dimmer.Multilevel_Switch.targetValue"));
    check("options: blank name falls back to id", named.keySet().stream().anyMatch(p -> p.startsWith("Node_001.")));

    Map<String, TagSpec> withConfig = byPath(model.buildTags(new ZWaveModel.Options(
        false, ZWaveModel.Options.parseExclusions("Version"), false, true)));
    check("options: Configuration included when not excluded", withConfig.containsKey("Node_005.Configuration.LED_indicator"));
    check("options: config param writable", withConfig.get("Node_005.Configuration.LED_indicator").writable());
    check("options: no _Info when disabled", withConfig.keySet().stream().noneMatch(p -> p.contains("._Info.")));

    Map<String, TagSpec> byNumber = byPath(model.buildTags(new ZWaveModel.Options(
        false, ZWaveModel.Options.parseExclusions("37, 112, 134"), true, true)));
    check("options: exclusion by number", byNumber.keySet().stream().noneMatch(p -> p.contains("Binary_Switch")));

    Map<String, TagSpec> readOnly = byPath(model.buildTags(new ZWaveModel.Options(
        false, ZWaveModel.Options.parseExclusions(""), true, false)));
    check("options: writes disabled", readOnly.values().stream().noneMatch(TagSpec::writable));

    check("options: stable across rebuilds",
        model.buildTags(defaults()).equals(model.buildTags(defaults())));
  }

  // ----------------------------------------------------------------- events

  private static void events(String stateJson) {
    ZWaveModel model = loaded(stateJson);
    String current = new ValueId(37, 0, "currentValue", null).key();

    ZWaveModel.Effect e = model.applyEvent(Json.parseObject(
        "{\"source\":\"node\",\"event\":\"value updated\",\"nodeId\":5,\"args\":{\"commandClassName\":\"Binary Switch\","
            + "\"commandClass\":37,\"endpoint\":0,\"property\":\"currentValue\",\"newValue\":true,\"prevValue\":false,"
            + "\"propertyName\":\"currentValue\"}}"));
    check("event: value updated is VALUE", e == ZWaveModel.Effect.VALUE);
    check("event: value applied", Boolean.TRUE.equals(model.value(5, current).value()));

    e = model.applyEvent(Json.parseObject(
        "{\"source\":\"node\",\"event\":\"value updated\",\"nodeId\":5,\"args\":{\"commandClassName\":\"Scene Activation\","
            + "\"commandClass\":43,\"endpoint\":0,\"property\":\"sceneId\",\"newValue\":3,\"propertyName\":\"sceneId\"}}"));
    check("event: unknown value is STRUCTURE", e == ZWaveModel.Effect.STRUCTURE);
    check("event: unknown value typed from first value",
        model.value(5, new ValueId(43, 0, "sceneId", null).key()).uaKind() == ZValue.UaKind.NUMBER);
    check("event: new value gets a tag",
        byPath(model.buildTags(defaults())).containsKey("Node_005.Scene_Activation.sceneId"));

    e = model.applyEvent(Json.parseObject(
        "{\"source\":\"node\",\"event\":\"value notification\",\"nodeId\":5,\"args\":{\"commandClass\":43,"
            + "\"endpoint\":0,\"property\":\"sceneId\",\"value\":4}}"));
    check("event: value notification", e == ZWaveModel.Effect.VALUE
        && Long.valueOf(4).equals(model.value(5, new ValueId(43, 0, "sceneId", null).key()).value()));

    e = model.applyEvent(Json.parseObject(
        "{\"source\":\"node\",\"event\":\"metadata updated\",\"nodeId\":5,\"args\":{\"commandClass\":37,\"endpoint\":0,"
            + "\"property\":\"currentValue\",\"metadata\":{\"type\":\"boolean\",\"readable\":true,\"writeable\":false,"
            + "\"label\":\"Current value\"}}}"));
    check("event: identical metadata is NONE", e == ZWaveModel.Effect.NONE);

    e = model.applyEvent(Json.parseObject(
        "{\"source\":\"node\",\"event\":\"metadata updated\",\"nodeId\":5,\"args\":{\"commandClass\":37,\"endpoint\":0,"
            + "\"property\":\"currentValue\",\"metadata\":{\"type\":\"boolean\",\"readable\":true,\"writeable\":false,"
            + "\"label\":\"Switch state\"}}}"));
    check("event: changed metadata is STRUCTURE", e == ZWaveModel.Effect.STRUCTURE);

    e = model.applyEvent(Json.parseObject("{\"source\":\"node\",\"event\":\"dead\",\"nodeId\":5,\"args\":{}}"));
    check("event: dead", e == ZWaveModel.Effect.NODE_STATUS && model.node(5).status() == ZNode.STATUS_DEAD);
    e = model.applyEvent(Json.parseObject("{\"source\":\"node\",\"event\":\"dead\",\"nodeId\":5}"));
    check("event: repeated status is NONE", e == ZWaveModel.Effect.NONE);
    model.applyEvent(Json.parseObject("{\"source\":\"node\",\"event\":\"alive\",\"nodeId\":5}"));
    check("event: alive", model.node(5).status() == ZNode.STATUS_ALIVE);
    model.applyEvent(Json.parseObject("{\"source\":\"node\",\"event\":\"wake up\",\"nodeId\":7}"));
    check("event: wake up", "Awake".equals(model.node(7).info("Status")));

    e = model.applyEvent(Json.parseObject(
        "{\"source\":\"node\",\"event\":\"value removed\",\"nodeId\":5,\"args\":{\"commandClass\":43,\"endpoint\":0,"
            + "\"property\":\"sceneId\",\"prevValue\":4}}"));
    check("event: value removed", e == ZWaveModel.Effect.STRUCTURE
        && model.value(5, new ValueId(43, 0, "sceneId", null).key()) == null);

    e = model.applyEvent(Json.parseObject(
        "{\"source\":\"node\",\"event\":\"value updated\",\"nodeId\":99,\"args\":{\"commandClass\":37,\"endpoint\":0,"
            + "\"property\":\"currentValue\",\"newValue\":true}}"));
    check("event: unknown node ignored", e == ZWaveModel.Effect.NONE);

    e = model.applyEvent(Json.parseObject(
        "{\"source\":\"controller\",\"event\":\"node added\",\"node\":{\"nodeId\":12,\"status\":0,\"ready\":false,"
            + "\"values\":[]},\"result\":{}}"));
    check("event: node added", e == ZWaveModel.Effect.STRUCTURE && model.node(12) != null);

    e = model.applyEvent(Json.parseObject(
        "{\"source\":\"node\",\"event\":\"ready\",\"nodeId\":12,\"nodeState\":{\"nodeId\":12,\"status\":4,"
            + "\"ready\":true,\"name\":\"Porch\",\"values\":[{\"commandClass\":37,\"commandClassName\":\"Binary Switch\","
            + "\"endpoint\":0,\"property\":\"currentValue\",\"propertyName\":\"currentValue\",\"metadata\":"
            + "{\"type\":\"boolean\",\"readable\":true,\"writeable\":false},\"value\":true}]}}"));
    check("event: ready replaces the node", e == ZWaveModel.Effect.STRUCTURE
        && model.node(12).ready() && "Porch".equals(model.node(12).name())
        && Boolean.TRUE.equals(model.value(12, current).value()));

    e = model.applyEvent(Json.parseObject(
        "{\"source\":\"controller\",\"event\":\"node removed\",\"node\":{\"nodeId\":12},\"reason\":0}"));
    check("event: node removed", e == ZWaveModel.Effect.STRUCTURE && model.node(12) == null);

    check("event: driver events ignored", model.applyEvent(Json.parseObject(
        "{\"source\":\"driver\",\"event\":\"all nodes ready\"}")) == ZWaveModel.Effect.NONE);

    // Old-schema object-keyed values still load.
    ZWaveModel legacy = new ZWaveModel();
    legacy.loadState(Json.parseObject(
        "{\"controller\":{\"ownNodeId\":1},\"nodes\":[{\"nodeId\":3,\"values\":{\"37-0-currentValue\":"
            + "{\"commandClass\":37,\"endpoint\":0,\"property\":\"currentValue\",\"metadata\":{\"type\":\"boolean\"},"
            + "\"value\":false}}}]}"));
    check("legacy: object-keyed values", legacy.value(3, current) != null);
  }

  // ---------------------------------------------------------------- helpers

  private static ZValue valueOfType(String type) {
    ZValue v = new ZValue(1, new ValueId(1, 0, "x", null));
    v.type = type;
    return v;
  }

  private static boolean throwsIae(Runnable r) {
    try {
      r.run();
      return false;
    } catch (IllegalArgumentException e) {
      return true;
    }
  }

  private static void check(String name, boolean ok) {
    check(name, ok, null);
  }

  private static void check(String name, boolean ok, String detail) {
    if (ok) {
      passed++;
    } else {
      failures.add(name + (detail == null ? "" : " -> " + detail));
    }
  }
}
