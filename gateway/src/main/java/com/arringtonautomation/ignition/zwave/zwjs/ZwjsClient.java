package com.arringtonautomation.ignition.zwave.zwjs;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client for the Z-Wave JS server WebSocket API (zwave-js-server, which Z-Wave JS UI embeds as its
 * "WS Server").
 *
 * <p>The protocol is JSON text frames. On connect the server sends a {@code version} message.
 * Commands go out as {@code {"messageId": "...", "command": "...", ...}} and come back as
 * {@code {"type": "result", "messageId": "...", "success": true, "result": {...}}}. After
 * {@code start_listening} the server pushes {@code {"type": "event", "event": {...}}} for every
 * change in the network.
 *
 * <p>Built on the JDK's own {@link java.net.http.WebSocket}, so the module needs no WebSocket
 * library. No Ignition or Milo types appear here, which keeps this class testable with plain javac.
 */
public final class ZwjsClient implements AutoCloseable {

  /** Receives server-pushed events and the end of the connection. Called on the socket thread. */
  public interface Listener {
    void onEvent(Map<String, Object> event);

    void onClosed(String reason);
  }

  private final URI uri;
  private final int timeoutMs;
  private final Listener listener;

  private final Map<String, CompletableFuture<Map<String, Object>>> pending =
      new ConcurrentHashMap<>();
  private final AtomicLong nextMessageId = new AtomicLong(1);
  private final CompletableFuture<Map<String, Object>> versionMessage = new CompletableFuture<>();
  private final Object sendLock = new Object();

  private volatile WebSocket socket;
  private volatile boolean closed;
  private volatile long lastPongMs;

  public ZwjsClient(URI uri, int timeoutMs, Listener listener) {
    this.uri = uri;
    this.timeoutMs = timeoutMs;
    this.listener = listener;
  }

  /**
   * Opens the socket and waits for the server's version message.
   *
   * @return the version message: driverVersion, serverVersion, homeId, minSchemaVersion,
   *     maxSchemaVersion.
   */
  public Map<String, Object> connect() throws ZwjsException {
    HttpClient http =
        HttpClient.newBuilder().connectTimeout(Duration.ofMillis(timeoutMs)).build();
    try {
      socket =
          http.newWebSocketBuilder()
              .connectTimeout(Duration.ofMillis(timeoutMs))
              .buildAsync(uri, new SocketListener())
              .get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (ExecutionException e) {
      throw new ZwjsException("Could not connect to " + uri + ": " + connectFailure(e), e);
    } catch (TimeoutException e) {
      throw new ZwjsException("Timed out connecting to " + uri, e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ZwjsException("Interrupted connecting to " + uri, e);
    }
    lastPongMs = System.currentTimeMillis();

    try {
      return versionMessage.get(timeoutMs, TimeUnit.MILLISECONDS);
    } catch (TimeoutException e) {
      close();
      throw new ZwjsException(
          "Connected to " + uri + " but no version message arrived - is this a Z-Wave JS server?",
          e);
    } catch (ExecutionException e) {
      close();
      throw new ZwjsException("Connection to " + uri + " failed: " + rootMessage(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      close();
      throw new ZwjsException("Interrupted waiting for the server version", e);
    }
  }

  /**
   * The server advertises a [min, max] API schema range. {@code configured} 0 means "newest the
   * server offers"; anything else is clamped into range so a stale pin degrades instead of failing.
   */
  public static int chooseSchema(Map<String, Object> version, int configured) {
    int min = Json.integer(version, "minSchemaVersion", 0);
    int max = Json.integer(version, "maxSchemaVersion", 0);
    int wanted = configured > 0 ? configured : max;
    return Math.max(min, Math.min(max, wanted));
  }

  /** Sends a command with the default timeout. */
  public Map<String, Object> request(String command, Map<String, Object> args)
      throws ZwjsException {
    return request(command, args, timeoutMs);
  }

  /**
   * Sends one command and blocks for its result.
   *
   * @return the {@code result} object of a successful response (empty if the server sent none).
   * @throws ZwjsException if the server reports failure, the socket drops, or the wait times out.
   */
  public Map<String, Object> request(String command, Map<String, Object> args, int waitMs)
      throws ZwjsException {
    WebSocket ws = socket;
    if (ws == null || closed) {
      throw new ZwjsException("Not connected");
    }

    String id = Long.toString(nextMessageId.getAndIncrement());
    Map<String, Object> message = new LinkedHashMap<>();
    message.put("messageId", id);
    message.put("command", command);
    if (args != null) {
      message.putAll(args);
    }

    CompletableFuture<Map<String, Object>> future = new CompletableFuture<>();
    pending.put(id, future);

    try {
      send(ws, Json.write(message));
      Map<String, Object> response = future.get(waitMs, TimeUnit.MILLISECONDS);
      return checkResult(command, response);
    } catch (TimeoutException e) {
      throw new ZwjsException(command + " timed out after " + waitMs + " ms", e);
    } catch (ExecutionException e) {
      throw new ZwjsException(command + " failed: " + rootMessage(e), e);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new ZwjsException(command + " was interrupted", e);
    } finally {
      pending.remove(id);
    }
  }

  /** Interprets a {@code result} frame. Package-private for the self-check. */
  static Map<String, Object> checkResult(String command, Map<String, Object> response)
      throws ZwjsException {
    if (!Json.bool(response, "success", false)) {
      String code = Json.str(response, "errorCode");
      String detail = Json.str(response, "message");
      if (detail == null) {
        detail = Json.str(response, "zwaveErrorMessage");
      }
      throw new ZwjsException(
          command
              + " was rejected by the server"
              + (code == null ? "" : " (" + code + ")")
              + (detail == null ? "" : ": " + detail),
          code);
    }
    Map<String, Object> result = Json.obj(response, "result");
    return result == null ? new LinkedHashMap<>() : result;
  }

  /** Sends a WebSocket ping; liveness is judged from {@link #millisSinceLastPong()}. */
  public void ping() {
    WebSocket ws = socket;
    if (ws != null && !closed) {
      synchronized (sendLock) {
        ws.sendPing(ByteBuffer.allocate(0));
      }
    }
  }

  public long millisSinceLastPong() {
    return System.currentTimeMillis() - lastPongMs;
  }

  public boolean isOpen() {
    WebSocket ws = socket;
    return ws != null && !closed && !ws.isInputClosed() && !ws.isOutputClosed();
  }

  @Override
  public void close() {
    if (closed) {
      return;
    }
    closed = true;
    WebSocket ws = socket;
    if (ws != null) {
      try {
        ws.sendClose(WebSocket.NORMAL_CLOSURE, "closing").orTimeout(1, TimeUnit.SECONDS);
      } catch (RuntimeException ignored) {
        // already gone
      }
      ws.abort();
    }
    failPending("Connection closed");
  }

  public String describe() {
    return uri.toString();
  }

  // ---------------------------------------------------------------- internals

  /**
   * java.net.http.WebSocket allows only one outstanding send at a time - a second sendText while
   * the first is in flight throws IllegalStateException. Writes from OPC clients and the
   * maintenance task can overlap, so sends are serialised and each waits for its own completion.
   */
  private void send(WebSocket ws, String text) throws ZwjsException {
    synchronized (sendLock) {
      try {
        ws.sendText(text, true).get(timeoutMs, TimeUnit.MILLISECONDS);
      } catch (ExecutionException | TimeoutException e) {
        throw new ZwjsException("Send failed: " + rootMessage(e), e);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        throw new ZwjsException("Send interrupted", e);
      }
    }
  }

  private void dispatch(String text) {
    Map<String, Object> message;
    try {
      message = Json.parseObject(text);
    } catch (IllegalArgumentException e) {
      return; // not ours to understand; the server never sends non-object frames
    }

    String type = Json.str(message, "type");
    if ("version".equals(type)) {
      versionMessage.complete(message);
    } else if ("result".equals(type)) {
      CompletableFuture<Map<String, Object>> f = pending.get(Json.str(message, "messageId"));
      if (f != null) {
        f.complete(message);
      }
    } else if ("event".equals(type)) {
      Map<String, Object> event = Json.obj(message, "event");
      if (event != null) {
        listener.onEvent(event);
      }
    }
  }

  private void onEnd(String reason) {
    boolean wasOpen = !closed;
    closed = true;
    versionMessage.completeExceptionally(new ZwjsException(reason));
    failPending(reason);
    if (wasOpen) {
      listener.onClosed(reason);
    }
  }

  private void failPending(String reason) {
    for (CompletableFuture<Map<String, Object>> f : pending.values()) {
      f.completeExceptionally(new ZwjsException(reason));
    }
  }

  /**
   * Plain-language reason for a failed connect. On Windows the JDK HTTP client reports a refused
   * TCP connection as a bare ClosedChannelException (or a ConnectException with no message), which
   * says nothing useful; in practice it means nothing is listening on that port. The usual cause
   * is the Z-Wave JS UI WS Server being off - and it stays off until Z-Wave JS UI's own driver has
   * opened the USB stick, so a missing or wrong serial port shows up here too.
   */
  static String connectFailure(Throwable t) {
    Throwable root = t;
    boolean refused = false;
    for (Throwable c = t; c != null; c = c.getCause() == c ? null : c.getCause()) {
      root = c;
      if (c instanceof java.net.ConnectException
          || c instanceof java.nio.channels.ClosedChannelException) {
        refused = true;
      }
    }
    if (refused) {
      return "connection refused - nothing is listening there. Check that Z-Wave JS UI is"
          + " running, that Settings > Home Assistant > WS Server is enabled and saved on that"
          + " host and port, and that Z-Wave JS UI is connected to the USB stick (the WS Server"
          + " only starts once it is)";
    }
    return rootMessage(root);
  }

  private static String rootMessage(Throwable t) {
    Throwable root = t;
    while (root.getCause() != null && root.getCause() != root) {
      root = root.getCause();
    }
    String m = root.getMessage();
    return m == null ? root.getClass().getSimpleName() : m;
  }

  private final class SocketListener implements WebSocket.Listener {
    /** A message can arrive in several frames; this collects them until {@code last}. */
    private final StringBuilder partial = new StringBuilder();

    @Override
    public void onOpen(WebSocket webSocket) {
      webSocket.request(1);
    }

    @Override
    public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
      partial.append(data);
      if (last) {
        String text = partial.toString();
        partial.setLength(0);
        try {
          dispatch(text);
        } catch (RuntimeException e) {
          // A bug in event handling must not kill the socket's read loop.
          System.err.println("Z-Wave JS event handling failed: " + e);
        }
      }
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
      lastPongMs = System.currentTimeMillis();
      webSocket.request(1);
      return null;
    }

    @Override
    public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
      onEnd(
          "Server closed the connection ("
              + statusCode
              + (reason == null || reason.isEmpty() ? "" : ": " + reason)
              + ")");
      return null;
    }

    @Override
    public void onError(WebSocket webSocket, Throwable error) {
      onEnd("Connection error: " + rootMessage(error));
    }
  }
}
