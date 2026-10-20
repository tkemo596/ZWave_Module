package com.arringtonautomation.ignition.zwave.zwjs;

import java.util.Map;

/**
 * One value on a Z-Wave node, as Z-Wave JS describes it: identity, metadata and the latest value.
 *
 * <p>The value fields are volatile and written by the socket thread; OPC reads take them without a
 * lock. Metadata changes only through {@link #applyMetadata}, which the model calls under its lock
 * and which always flags a tree rebuild, so readers never see a half-updated type.
 */
public final class ZValue {

  public final ValueId id;
  public final int nodeId;

  volatile String commandClassName;
  volatile String propertyName;
  volatile String propertyKeyName;

  /** Z-Wave JS metadata type: number, boolean, string, duration, color, buffer, any, number[]... */
  volatile String type = "any";

  volatile boolean readable = true;
  volatile boolean writeable = false;
  volatile String label;
  volatile String unit;
  volatile String description;
  volatile Map<String, Object> states;
  volatile Double min;
  volatile Double max;

  private volatile Object value;
  private volatile boolean hasValue;
  private volatile long updatedMs;

  ZValue(int nodeId, ValueId id) {
    this.nodeId = nodeId;
    this.id = id;
  }

  /** Builds a value from one entry of a NodeState's {@code values}. */
  static ZValue fromState(int nodeId, Map<String, Object> state) {
    ZValue v = new ZValue(nodeId, ValueId.fromJson(state));
    v.applyNames(state);
    v.applyMetadata(Json.obj(state, "metadata"));
    if (state.containsKey("value")) {
      v.setValue(state.get("value"));
    }
    return v;
  }

  void applyNames(Map<String, Object> m) {
    String cc = Json.str(m, "commandClassName");
    if (cc != null) {
      commandClassName = cc;
    }
    String pn = Json.str(m, "propertyName");
    if (pn != null) {
      propertyName = pn;
    }
    String pkn = Json.str(m, "propertyKeyName");
    if (pkn != null) {
      propertyKeyName = pkn;
    }
  }

  void applyMetadata(Map<String, Object> md) {
    if (md == null) {
      return;
    }
    String t = Json.str(md, "type");
    type = t == null ? "any" : t;
    readable = Json.bool(md, "readable", true);
    writeable = Json.bool(md, "writeable", false);
    label = Json.str(md, "label");
    unit = Json.str(md, "unit");
    description = Json.str(md, "description");
    states = Json.obj(md, "states");
    min = md.get("min") instanceof Number n ? n.doubleValue() : null;
    max = md.get("max") instanceof Number n ? n.doubleValue() : null;
  }

  void setValue(Object v) {
    value = v;
    hasValue = true;
    updatedMs = System.currentTimeMillis();
  }

  public Object value() {
    return value;
  }

  public boolean hasValue() {
    return hasValue;
  }

  public long updatedMs() {
    return updatedMs;
  }

  public String type() {
    return type;
  }

  public boolean readable() {
    return readable;
  }

  public boolean writeable() {
    return writeable;
  }

  public String commandClassName() {
    return commandClassName;
  }

  public String propertyName() {
    return propertyName;
  }

  public String propertyKeyName() {
    return propertyKeyName;
  }

  public Double min() {
    return min;
  }

  public Double max() {
    return max;
  }

  /** How this value is presented in OPC UA. Composite Z-Wave types travel as JSON strings. */
  public UaKind uaKind() {
    return switch (type) {
      case "number" -> UaKind.NUMBER;
      case "boolean" -> UaKind.BOOLEAN;
      case "string" -> UaKind.STRING;
      default -> UaKind.JSON;
    };
  }

  /** Human description for the OPC node: label, unit and any enumerated states. */
  public String describe() {
    StringBuilder sb = new StringBuilder();
    if (label != null) {
      sb.append(label);
    }
    if (unit != null && !unit.isEmpty() && (label == null || !label.contains("[" + unit + "]"))) {
      sb.append(sb.length() > 0 ? " " : "").append('[').append(unit).append(']');
    }
    Map<String, Object> s = states;
    if (s != null && !s.isEmpty()) {
      sb.append(sb.length() > 0 ? " " : "").append(Json.write(s));
    }
    if (description != null && !description.isEmpty()) {
      sb.append(sb.length() > 0 ? " - " : "").append(description);
    }
    return sb.toString();
  }

  public enum UaKind {
    NUMBER,
    BOOLEAN,
    STRING,
    JSON
  }
}
