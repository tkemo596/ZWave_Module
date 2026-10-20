package com.arringtonautomation.ignition.zwave.zwjs;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One Z-Wave node and its values. */
public final class ZNode {

  /** Z-Wave JS NodeStatus. */
  public static final int STATUS_UNKNOWN = 0;
  public static final int STATUS_ASLEEP = 1;
  public static final int STATUS_AWAKE = 2;
  public static final int STATUS_DEAD = 3;
  public static final int STATUS_ALIVE = 4;

  public final int nodeId;

  volatile String name;
  volatile String location;
  volatile int status;
  volatile boolean ready;
  volatile boolean controllerNode;
  volatile String manufacturer;
  volatile String product;
  volatile String productDescription;
  volatile String firmwareVersion;

  /**
   * Values by {@link ValueId#key()}. Concurrent because OPC read threads look values up while the
   * socket thread adds and removes them; the tree builder sorts, so map order does not matter.
   */
  final Map<String, ZValue> values = new ConcurrentHashMap<>();

  ZNode(int nodeId) {
    this.nodeId = nodeId;
  }

  /** Builds a node from a NodeState object (start_listening, "ready" and "node added"). */
  static ZNode fromState(Map<String, Object> state) {
    ZNode n = new ZNode(Json.integer(state, "nodeId", -1));
    n.applyInfo(state);

    // NodeState.values is an array of ValueState. Very old schemas sent an object keyed by value
    // id instead; accept both rather than tie the driver to one server release.
    List<Object> list = Json.list(state, "values");
    if (list != null) {
      for (Object o : list) {
        n.addValueState(o);
      }
    } else {
      Map<String, Object> map = Json.obj(state, "values");
      if (map != null) {
        for (Object o : map.values()) {
          n.addValueState(o);
        }
      }
    }
    return n;
  }

  @SuppressWarnings("unchecked")
  private void addValueState(Object o) {
    if (o instanceof Map<?, ?> m) {
      ZValue v = ZValue.fromState(nodeId, (Map<String, Object>) m);
      values.put(v.id.key(), v);
    }
  }

  void applyInfo(Map<String, Object> state) {
    name = Json.str(state, "name");
    location = Json.str(state, "location");
    status = Json.integer(state, "status", STATUS_UNKNOWN);
    ready = Json.bool(state, "ready", false);
    controllerNode = Json.bool(state, "isControllerNode", false);
    firmwareVersion = Json.str(state, "firmwareVersion");

    Map<String, Object> dc = Json.obj(state, "deviceConfig");
    manufacturer = Json.str(dc, "manufacturer");
    product = Json.str(dc, "label");
    productDescription = Json.str(dc, "description");
    if (product == null) {
      product = Json.str(state, "label");
    }
  }

  public String name() {
    return name;
  }

  public String location() {
    return location;
  }

  public int status() {
    return status;
  }

  public boolean ready() {
    return ready;
  }

  public boolean isControllerNode() {
    return controllerNode;
  }

  public String statusName() {
    return switch (status) {
      case STATUS_ASLEEP -> "Asleep";
      case STATUS_AWAKE -> "Awake";
      case STATUS_DEAD -> "Dead";
      case STATUS_ALIVE -> "Alive";
      default -> "Unknown";
    };
  }

  /** Info fields exposed under each node's _Info folder. */
  public Object info(String field) {
    return switch (field) {
      case "NodeId" -> (long) nodeId;
      case "Name" -> name == null ? "" : name;
      case "Location" -> location == null ? "" : location;
      case "Status" -> statusName();
      case "Ready" -> ready;
      case "Manufacturer" -> manufacturer == null ? "" : manufacturer;
      case "Product" -> product == null ? "" : product;
      case "Description" -> productDescription == null ? "" : productDescription;
      case "FirmwareVersion" -> firmwareVersion == null ? "" : firmwareVersion;
      default -> null;
    };
  }

  public static final List<String> INFO_FIELDS =
      List.of(
          "NodeId",
          "Name",
          "Location",
          "Status",
          "Ready",
          "Manufacturer",
          "Product",
          "Description",
          "FirmwareVersion");
}
