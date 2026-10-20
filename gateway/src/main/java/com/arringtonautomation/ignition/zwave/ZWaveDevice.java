package com.arringtonautomation.ignition.zwave;

import com.arringtonautomation.ignition.zwave.zwjs.Json;
import com.arringtonautomation.ignition.zwave.zwjs.TagSpec;
import com.arringtonautomation.ignition.zwave.zwjs.ValueCodec;
import com.arringtonautomation.ignition.zwave.zwjs.ZNode;
import com.arringtonautomation.ignition.zwave.zwjs.ZValue;
import com.arringtonautomation.ignition.zwave.zwjs.ZWaveModel;
import com.arringtonautomation.ignition.zwave.zwjs.ZwjsClient;
import com.arringtonautomation.ignition.zwave.zwjs.ZwjsException;
import com.inductiveautomation.ignition.gateway.opcua.server.api.Device;
import com.inductiveautomation.ignition.gateway.opcua.server.api.DeviceContext;
import java.net.URI;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.eclipse.milo.opcua.sdk.core.AccessLevel;
import org.eclipse.milo.opcua.sdk.core.Reference;
import org.eclipse.milo.opcua.sdk.server.ManagedAddressSpaceWithLifecycle;
import org.eclipse.milo.opcua.sdk.server.items.DataItem;
import org.eclipse.milo.opcua.sdk.server.items.MonitoredItem;
import org.eclipse.milo.opcua.sdk.server.nodes.UaFolderNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaNode;
import org.eclipse.milo.opcua.sdk.server.nodes.UaVariableNode;
import org.eclipse.milo.opcua.sdk.server.nodes.filters.AttributeFilters;
import org.eclipse.milo.opcua.sdk.server.util.SubscriptionModel;
import org.eclipse.milo.opcua.stack.core.AttributeId;
import org.eclipse.milo.opcua.stack.core.NodeIds;
import org.eclipse.milo.opcua.stack.core.OpcUaDataType;
import org.eclipse.milo.opcua.stack.core.StatusCodes;
import org.eclipse.milo.opcua.stack.core.UaRuntimeException;
import org.eclipse.milo.opcua.stack.core.types.builtin.DataValue;
import org.eclipse.milo.opcua.stack.core.types.builtin.DateTime;
import org.eclipse.milo.opcua.stack.core.types.builtin.LocalizedText;
import org.eclipse.milo.opcua.stack.core.types.builtin.NodeId;
import org.eclipse.milo.opcua.stack.core.types.builtin.StatusCode;
import org.eclipse.milo.opcua.stack.core.types.builtin.Variant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One configured connection to a Z-Wave JS server, exposed as a branch of Ignition's OPC UA address
 * space.
 *
 * <p>Structurally this is a conventional 8.3 device driver with the scan loop taken out. A single
 * maintenance task owns the connection - dial, handshake, {@code start_listening}, rebuild the node
 * tree - and keeps it alive with WebSocket pings. Values arrive as server-pushed events on the
 * socket thread and land in the {@link ZWaveModel}, which the nodes' attribute filters read
 * directly. Ignition's subscription sampling then picks changes up at whatever rate the tag group
 * asks for; there is no polling of the Z-Wave network at all, which matters because Z-Wave is a
 * 100 kbit/s mesh and polling it is how networks get slow.
 *
 * <p>Tree rebuilds are debounced. Including a device produces a burst of "value added" and
 * "metadata updated" events over several seconds; the tree is rebuilt once the burst goes quiet,
 * not once per event.
 */
public class ZWaveDevice extends ManagedAddressSpaceWithLifecycle implements Device {

  private static final Logger logger = LoggerFactory.getLogger(ZWaveDevice.class);

  private static final int MAINTENANCE_PERIOD_MS = 1000;

  /** Quiet time after the last structural event before the tree is rebuilt. */
  private static final long REBUILD_DEBOUNCE_MS = 2000;

  private static final long PING_INTERVAL_MS = 15_000;

  /** No pong for this long and the connection is treated as dead. */
  private static final long PONG_TIMEOUT_MS = 45_000;

  /** start_listening returns the whole network; give a big one longer than a normal command. */
  private static final int START_LISTENING_TIMEOUT_MS = 60_000;

  private final DeviceContext context;
  private final ZWaveDeviceConfig config;
  private final SubscriptionModel subscriptionModel;
  private final ZWaveModel model = new ZWaveModel();
  private final ZWaveModel.Options options;

  private final ReentrantLock treeLock = new ReentrantLock();

  /** Every node this device added, in creation order, so a rebuild can undo itself. */
  private final List<NodeId> managedNodes = new ArrayList<>();

  /** Events that arrive while a connect is still loading the initial state. */
  private final ConcurrentLinkedQueue<Map<String, Object>> earlyEvents =
      new ConcurrentLinkedQueue<>();

  private volatile ZwjsClient client;
  private volatile boolean listening;
  private volatile String status = "Disconnected";
  private volatile boolean rebuildNeeded;
  private volatile long lastStructuralChangeMs;
  private volatile int tagCount;

  private long nextConnectAttemptMs;
  private long nextPingMs;

  public ZWaveDevice(DeviceContext context, ZWaveDeviceConfig config) {
    super(context.getServer());

    this.context = context;
    this.config = config;
    this.options =
        new ZWaveModel.Options(
            config.browse().nameNodeFolders(),
            ZWaveModel.Options.parseExclusions(config.browse().excludedCommandClasses()),
            config.browse().includeNodeInfo(),
            config.writes().writesEnabled());

    this.subscriptionModel = new SubscriptionModel(context.getServer(), this);

    getLifecycleManager().addLifecycle(subscriptionModel);
    getLifecycleManager().addStartupTask(this::onStartup);
    getLifecycleManager().addShutdownTask(this::onShutdown);
  }

  @Override
  public String getStatus() {
    return status;
  }

  // ---------------------------------------------------------------- lifecycle

  private void onStartup() {
    context
        .getGatewayContext()
        .getExecutionManager()
        .registerAtFixedRate(
            ZWaveDeviceExtensionPoint.TYPE_ID,
            maintenanceTaskName(),
            this::runMaintenance,
            MAINTENANCE_PERIOD_MS,
            TimeUnit.MILLISECONDS);

    onDataItemsCreated(context.getSubscriptionModel().getDataItems(context.getName()));
  }

  private void onShutdown() {
    context
        .getSubscriptionModel()
        .getDataItems(context.getName())
        .forEach(item -> item.setQuality(new StatusCode(StatusCodes.Uncertain_LastUsableValue)));

    context
        .getGatewayContext()
        .getExecutionManager()
        .unRegister(ZWaveDeviceExtensionPoint.TYPE_ID, maintenanceTaskName());

    closeClient();
    status = "Disconnected";
  }

  private String maintenanceTaskName() {
    return context.getName() + "/maintenance";
  }

  // ------------------------------------------------------------- maintenance

  private void runMaintenance() {
    try {
      ZwjsClient c = client;
      long now = System.currentTimeMillis();

      if (c == null || !c.isOpen()) {
        if (c != null) {
          // Dropped since the last pass; the socket listener has already logged why.
          closeClient();
        }
        if (now >= nextConnectAttemptMs) {
          openConnection();
        }
        return;
      }

      if (now >= nextPingMs) {
        nextPingMs = now + PING_INTERVAL_MS;
        if (c.millisSinceLastPong() > PONG_TIMEOUT_MS) {
          logger.warn(
              "Z-Wave JS server at {} stopped answering pings; reconnecting", c.describe());
          status = "Error: server stopped responding";
          closeClient();
          scheduleReconnect();
          return;
        }
        c.ping();
      }

      if (rebuildNeeded && now - lastStructuralChangeMs >= REBUILD_DEBOUNCE_MS) {
        rebuildNeeded = false;
        rebuildNodeTree();
        status = connectedStatus();
      }

    } catch (RuntimeException e) {
      logger.error("Unexpected error maintaining device {}", context.getName(), e);
      status = "Error: " + e;
    }
  }

  private void openConnection() {
    closeClient();
    scheduleReconnect();

    URI uri;
    try {
      uri = URI.create(config.connection().serverUrl().trim());
    } catch (RuntimeException e) {
      status = "Configuration error: bad Server URL";
      return;
    }

    status = "Connecting to " + uri + "...";
    ZwjsClient c =
        new ZwjsClient(
            uri,
            Math.max(config.connection().timeoutMs(), 500),
            new ZwjsClient.Listener() {
              @Override
              public void onEvent(Map<String, Object> event) {
                handleEvent(event);
              }

              @Override
              public void onClosed(String reason) {
                handleClosed(reason);
              }
            });

    try {
      Map<String, Object> version = c.connect();
      int schema = ZwjsClient.chooseSchema(version, config.connection().schemaVersion());

      c.request("set_api_schema", Map.of("schemaVersion", schema));

      // Anything pushed between start_listening's reply and the state being loaded must not be
      // applied to the old model and then wiped; it is queued and replayed below.
      listening = false;
      earlyEvents.clear();
      client = c;

      Map<String, Object> result = c.request("start_listening", null, START_LISTENING_TIMEOUT_MS);
      Map<String, Object> state = Json.obj(result, "state");
      if (state == null) {
        throw new ZwjsException("start_listening returned no state");
      }

      model.setServerInfo(version, schema);
      model.loadState(state);
      listening = true;

      Map<String, Object> early;
      while ((early = earlyEvents.poll()) != null) {
        model.applyEvent(early);
      }

      rebuildNodeTree();
      rebuildNeeded = false;
      nextPingMs = System.currentTimeMillis() + PING_INTERVAL_MS;
      status = connectedStatus();

      logger.info(
          "Device {} connected to {} - Z-Wave JS {} / server {}, schema {}, {} nodes, {} tags",
          context.getName(),
          uri,
          version.get("driverVersion"),
          version.get("serverVersion"),
          schema,
          model.nodeCount(),
          tagCount);

    } catch (ZwjsException e) {
      status = "Disconnected: " + e.getMessage();
      logger.warn("Device {} could not connect: {}", context.getName(), e.getMessage());
      closeClient();
    } catch (RuntimeException e) {
      status = "Error: " + e;
      logger.error("Device {} failed while connecting", context.getName(), e);
      closeClient();
    }
  }

  private void scheduleReconnect() {
    nextConnectAttemptMs =
        System.currentTimeMillis() + Math.max(config.connection().reconnectSeconds(), 1) * 1000L;
  }

  private void closeClient() {
    ZwjsClient c = client;
    client = null;
    listening = false;
    if (c != null) {
      c.close();
    }
  }

  private String connectedStatus() {
    return "Connected - " + model.nodeCount() + " nodes, " + tagCount + " tags";
  }

  // ------------------------------------------------------------------ events

  /** Socket thread. Must be quick and must never call back into the client synchronously. */
  private void handleEvent(Map<String, Object> event) {
    if (!listening) {
      earlyEvents.add(event);
      return;
    }
    ZWaveModel.Effect effect = model.applyEvent(event);
    if (effect == ZWaveModel.Effect.STRUCTURE) {
      lastStructuralChangeMs = System.currentTimeMillis();
      rebuildNeeded = true;
    }
  }

  private void handleClosed(String reason) {
    logger.warn("Device {} lost its connection: {}", context.getName(), reason);
    status = "Disconnected: " + reason;
    listening = false;
    scheduleReconnect();
  }

  // --------------------------------------------------------------- node tree

  private void rebuildNodeTree() {
    List<TagSpec> specs = model.buildTags(options);

    treeLock.lock();
    try {
      removeAllNodes();

      UaFolderNode rootNode =
          new UaFolderNode(
              getNodeContext(),
              context.nodeId(context.getName()),
              context.qualifiedName(String.format("[%s]", context.getName())),
              new LocalizedText(String.format("[%s]", context.getName())));

      getNodeManager().addNode(rootNode);
      managedNodes.add(rootNode.getNodeId());

      rootNode.addReference(
          new Reference(
              rootNode.getNodeId(),
              NodeIds.Organizes,
              context.getRootNodeId().expanded(),
              Reference.Direction.INVERSE));

      Map<String, UaFolderNode> folders = new HashMap<>();
      folders.put("", rootNode);

      for (TagSpec spec : specs) {
        UaFolderNode parent = folderFor(folders, spec.parentPath());
        UaVariableNode node = buildVariableNode(spec);
        getNodeManager().addNode(node);
        managedNodes.add(node.getNodeId());
        parent.addOrganizes(node);
      }

      tagCount = specs.size();

    } finally {
      treeLock.unlock();
    }
  }

  /** Creates folders for every segment of {@code path} that does not yet exist. Memoised. */
  private UaFolderNode folderFor(Map<String, UaFolderNode> folders, String path) {
    UaFolderNode existing = folders.get(path);
    if (existing != null) {
      return existing;
    }

    int dot = path.lastIndexOf('.');
    String parentPath = dot < 0 ? "" : path.substring(0, dot);
    String name = dot < 0 ? path : path.substring(dot + 1);

    UaFolderNode parent = folderFor(folders, parentPath);
    UaFolderNode folder =
        new UaFolderNode(
            getNodeContext(),
            context.nodeId(path),
            context.qualifiedName(name),
            new LocalizedText(name));

    getNodeManager().addNode(folder);
    managedNodes.add(folder.getNodeId());
    parent.addOrganizes(folder);
    folders.put(path, folder);
    return folder;
  }

  private UaVariableNode buildVariableNode(TagSpec spec) {
    Set<AccessLevel> accessLevel = spec.writable() ? AccessLevel.READ_WRITE : AccessLevel.READ_ONLY;

    UaVariableNode node =
        UaVariableNode.build(
            getNodeContext(),
            b ->
                b.setNodeId(context.nodeId(spec.path()))
                    .setBrowseName(context.qualifiedName(spec.leafName()))
                    .setDisplayName(new LocalizedText(spec.leafName()))
                    .setDescription(new LocalizedText(spec.description()))
                    .setDataType(uaType(spec.uaKind()).getNodeId())
                    .setTypeDefinition(NodeIds.BaseDataVariableType)
                    .setAccessLevel(accessLevel)
                    .setUserAccessLevel(accessLevel)
                    .build());

    node.getFilterChain().addLast(AttributeFilters.getValue(ctx -> readValue(spec)));

    if (spec.writable()) {
      node.getFilterChain()
          .addLast(
              AttributeFilters.setValue(
                  (ctx, value) -> {
                    writeValue(spec, value);
                    ctx.setAttribute(AttributeId.Value, value);
                  }));
    }
    return node;
  }

  private static OpcUaDataType uaType(ZValue.UaKind kind) {
    return switch (kind) {
      case NUMBER -> OpcUaDataType.Double;
      case BOOLEAN -> OpcUaDataType.Boolean;
      case STRING, JSON -> OpcUaDataType.String;
    };
  }

  private void removeAllNodes() {
    for (int i = managedNodes.size() - 1; i >= 0; i--) {
      NodeId nodeId = managedNodes.get(i);
      try {
        getNodeManager().removeNode(nodeId);
      } catch (RuntimeException e) {
        logger.debug("Ignoring an error removing node {}: {}", nodeId, e.toString());
      }
    }
    managedNodes.clear();
  }

  // ------------------------------------------------------------------- reads

  private DataValue readValue(TagSpec spec) {
    boolean connected = listening && client != null;

    switch (spec.kind()) {
      case SERVER_INFO:
        return good(model.serverInfo(spec.field(), connected), DateTime.now());

      case NODE_COMMAND:
        return good(Boolean.FALSE, DateTime.now());

      case NODE_INFO:
        {
          ZNode node = model.node(spec.nodeId());
          if (node == null) {
            return bad(StatusCodes.Bad_NodeIdUnknown);
          }
          return withConnection(good(node.info(spec.field()), DateTime.now()), connected);
        }

      case VALUE:
      default:
        {
          ZValue v = model.value(spec.nodeId(), spec.valueKey());
          if (v == null) {
            return bad(StatusCodes.Bad_NodeIdUnknown);
          }
          if (!v.hasValue()) {
            // Write-only values (most targetValue properties) never report; serve a good null so
            // the tag is usable for writing instead of showing a permanent bad quality.
            return v.readable()
                ? bad(StatusCodes.Bad_WaitingForInitialData)
                : good(null, DateTime.now());
          }

          Object ua = ValueCodec.toUa(v.uaKind(), v.value());
          DataValue dv = good(ua, sourceTime(v));

          ZNode node = model.node(spec.nodeId());
          if (node != null && node.status() == ZNode.STATUS_DEAD) {
            return new DataValue(
                dv.getValue(), new StatusCode(StatusCodes.Bad_NoCommunication), sourceTime(v));
          }
          return withConnection(dv, connected);
        }
    }
  }

  /**
   * When the value last changed, not when it was read - so an unchanged value keeps its timestamp
   * across samples, and Ignition's tag history sees the real change time.
   */
  private static DateTime sourceTime(ZValue v) {
    return new DateTime(new Date(v.updatedMs()));
  }

  private static DataValue good(Object value, DateTime time) {
    return new DataValue(
        value == null ? Variant.NULL_VALUE : new Variant(value), StatusCode.GOOD, time);
  }

  private static DataValue bad(long statusCode) {
    return new DataValue(Variant.NULL_VALUE, new StatusCode(statusCode), DateTime.now());
  }

  /** While disconnected every value is the last one we saw, and says so. */
  private static DataValue withConnection(DataValue dv, boolean connected) {
    if (connected) {
      return dv;
    }
    return new DataValue(
        dv.getValue(), new StatusCode(StatusCodes.Uncertain_LastUsableValue), DateTime.now());
  }

  // ------------------------------------------------------------------ writes

  /**
   * Called on an OPC UA server thread; blocks until the server answers or the request times out,
   * the usual contract for device driver writes.
   */
  private void writeValue(TagSpec spec, DataValue value) {
    ZwjsClient c = client;
    if (c == null || !listening) {
      throw new UaRuntimeException(
          StatusCodes.Bad_NotConnected, "Not connected to " + context.getName());
    }
    Object raw = value.getValue() == null ? null : value.getValue().getValue();

    try {
      if (spec.kind() == TagSpec.Kind.NODE_COMMAND) {
        if (Boolean.TRUE.equals(ValueCodec.toBoolean(raw))) {
          String command =
              "Ping".equals(spec.field()) ? "node.ping" : "node.refresh_values";
          c.request(command, Map.of("nodeId", spec.nodeId()));
        }
        return;
      }

      ZValue v = model.value(spec.nodeId(), spec.valueKey());
      if (v == null) {
        throw new UaRuntimeException(StatusCodes.Bad_NodeIdUnknown, spec.path() + " no longer exists");
      }

      Object payload = ValueCodec.toZwave(v, raw);

      Map<String, Object> args = new LinkedHashMap<>();
      args.put("nodeId", spec.nodeId());
      args.put("valueId", v.id.toJson());
      args.put("value", payload);

      Map<String, Object> result = c.request("node.set_value", args);
      String failure = ValueCodec.setValueFailure(result);
      if (failure != null) {
        throw new UaRuntimeException(
            StatusCodes.Bad_DeviceFailure, "Write to " + spec.path() + " failed: " + failure);
      }

    } catch (IllegalArgumentException e) {
      throw new UaRuntimeException(
          StatusCodes.Bad_TypeMismatch, "Cannot write to " + spec.path() + ": " + e.getMessage());
    } catch (ZwjsException e) {
      throw new UaRuntimeException(
          StatusCodes.Bad_DeviceFailure, "Write to " + spec.path() + " failed: " + e.getMessage());
    }
  }

  // ----------------------------------------------------- subscription plumbing

  @Override
  public void onDataItemsCreated(List<DataItem> dataItems) {
    subscriptionModel.onDataItemsCreated(dataItems);
  }

  @Override
  public void onDataItemsModified(List<DataItem> dataItems) {
    subscriptionModel.onDataItemsModified(dataItems);
  }

  @Override
  public void onDataItemsDeleted(List<DataItem> dataItems) {
    subscriptionModel.onDataItemsDeleted(dataItems);
  }

  @Override
  public void onMonitoringModeChanged(List<MonitoredItem> monitoredItems) {
    subscriptionModel.onMonitoringModeChanged(monitoredItems);
  }
}
