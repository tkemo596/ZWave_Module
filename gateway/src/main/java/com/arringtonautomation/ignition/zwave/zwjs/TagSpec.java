package com.arringtonautomation.ignition.zwave.zwjs;

/**
 * One OPC UA variable the device should expose, as decided by {@link ZWaveModel#buildTags}.
 *
 * <p>A spec carries addresses, never live objects - the value it serves is looked up in the model
 * on every read - so a node being re-interviewed and replaced does not leave nodes holding stale
 * references.
 *
 * @param path dotted path below the device root, e.g. {@code Node_005.Binary_Switch.currentValue}
 * @param kind what the tag reads from
 * @param nodeId the Z-Wave node, or -1 for server-level tags
 * @param valueKey {@link ValueId#key()} for {@link Kind#VALUE} tags
 * @param field info field or command name for the other kinds
 * @param uaKind how the value is typed in OPC UA
 * @param writable whether the OPC node accepts writes
 * @param description shown as the node's Description
 */
public record TagSpec(
    String path,
    Kind kind,
    int nodeId,
    String valueKey,
    String field,
    ZValue.UaKind uaKind,
    boolean writable,
    String description) {

  public enum Kind {
    /** A Z-Wave JS value. */
    VALUE,
    /** A node property such as Status or Name. */
    NODE_INFO,
    /** A write-to-trigger command on a node: RefreshValues, Ping. */
    NODE_COMMAND,
    /** A property of the server connection: Connected, DriverVersion and so on. */
    SERVER_INFO
  }

  public String leafName() {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? path : path.substring(dot + 1);
  }

  public String parentPath() {
    int dot = path.lastIndexOf('.');
    return dot < 0 ? "" : path.substring(0, dot);
  }
}
