package com.arringtonautomation.ignition.zwave.zwjs;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentSkipListMap;

/**
 * The driver's picture of the Z-Wave network: every node and value, kept current from the server's
 * event stream, plus the rules that turn it into a tag tree.
 *
 * <p>This plays the role a PLC driver's symbol table and browse expander would. The difference
 * that shapes the whole design: a PLC driver polls because the PLC must be asked,
 * whereas Z-Wave JS pushes. Every change on the network arrives as a {@code value updated} event, so
 * the model <em>is</em> the value cache and there is no scan loop.
 *
 * <p>Thread model: {@link #loadState} and {@link #applyEvent} mutate under the model's monitor and
 * are called by one thread at a time in practice (the socket thread, or the maintenance thread
 * during connect). Readers - OPC attribute filters - use {@link #value} and {@link #node}, which go
 * through concurrent maps and volatile fields and never block.
 */
public final class ZWaveModel {

  /** What an event changed, so the device knows whether to rebuild its node tree. */
  public enum Effect {
    NONE,
    /** A value changed. Nothing to do: reads come straight from the model. */
    VALUE,
    /** A node's status or readiness changed; affects tag quality, not tree shape. */
    NODE_STATUS,
    /** Nodes or values appeared, disappeared or changed type. The tree must be rebuilt. */
    STRUCTURE
  }

  /** Command classes hidden by default: device-management plumbing, not process data. */
  public static final String DEFAULT_EXCLUDED_CCS =
      "Configuration, Association, Multi Channel Association, Association Group Information,"
          + " Version, Manufacturer Specific, Firmware Update Meta Data, Z-Wave Plus Info,"
          + " Node Naming and Location";

  public static final List<String> SERVER_FIELDS =
      List.of(
          "Connected",
          "DriverVersion",
          "ServerVersion",
          "SchemaVersion",
          "HomeId",
          "ControllerNodeId",
          "NodeCount");

  public static final List<String> NODE_COMMANDS = List.of("RefreshValues", "Ping");

  private final Map<Integer, ZNode> nodes = new ConcurrentSkipListMap<>();

  private volatile String driverVersion = "";
  private volatile String serverVersion = "";
  private volatile long homeId;
  private volatile int controllerNodeId;
  private volatile int schemaVersion;

  // ------------------------------------------------------------------ loading

  public synchronized void setServerInfo(Map<String, Object> version, int schema) {
    driverVersion = String.valueOf(version.getOrDefault("driverVersion", ""));
    serverVersion = String.valueOf(version.getOrDefault("serverVersion", ""));
    Object h = version.get("homeId");
    homeId = h instanceof Number n ? n.longValue() : 0;
    schemaVersion = schema;
  }

  /** Replaces everything with the {@code state} object from {@code start_listening}. */
  public synchronized void loadState(Map<String, Object> state) {
    nodes.clear();

    Map<String, Object> controller = Json.obj(state, "controller");
    controllerNodeId = Json.integer(controller, "ownNodeId", 0);
    Object h = controller == null ? null : controller.get("homeId");
    if (h instanceof Number n) {
      homeId = n.longValue();
    }

    List<Object> list = Json.list(state, "nodes");
    if (list != null) {
      for (Object o : list) {
        if (o instanceof Map<?, ?> m) {
          @SuppressWarnings("unchecked")
          ZNode node = ZNode.fromState((Map<String, Object>) m);
          if (node.nodeId > 0) {
            nodes.put(node.nodeId, node);
          }
        }
      }
    }
  }

  // ------------------------------------------------------------------- events

  /** Applies one server event. {@code event} is the inner object of an event frame. */
  public synchronized Effect applyEvent(Map<String, Object> event) {
    String source = Json.str(event, "source");
    String name = Json.str(event, "event");
    if (name == null) {
      return Effect.NONE;
    }

    if ("controller".equals(source)) {
      return applyControllerEvent(name, event);
    }
    if (!"node".equals(source)) {
      return Effect.NONE;
    }

    int nodeId = Json.integer(event, "nodeId", -1);
    Map<String, Object> args = Json.obj(event, "args");

    // "ready" carries the full, freshly interviewed node. Take it whole: it is the authoritative
    // answer to every question the interview raised, including values we were never told about.
    if ("ready".equals(name)) {
      Map<String, Object> nodeState = Json.obj(event, "nodeState");
      if (nodeState == null) {
        nodeState = Json.obj(args, "nodeState");
      }
      if (nodeState != null) {
        ZNode fresh = ZNode.fromState(nodeState);
        if (fresh.nodeId > 0) {
          nodes.put(fresh.nodeId, fresh);
          return Effect.STRUCTURE;
        }
      }
      ZNode node = nodes.get(nodeId);
      if (node != null) {
        node.ready = true;
        return Effect.NODE_STATUS;
      }
      return Effect.NONE;
    }

    ZNode node = nodes.get(nodeId);
    if (node == null) {
      return Effect.NONE;
    }

    switch (name) {
      case "value updated":
        return upsertValue(node, args, "newValue");
      case "value notification":
        return upsertValue(node, args, "value");
      case "value added":
        return upsertValue(node, args, "newValue");
      case "value removed":
        {
          if (args == null) {
            return Effect.NONE;
          }
          ZValue removed = node.values.remove(ValueId.fromJson(args).key());
          return removed == null ? Effect.NONE : Effect.STRUCTURE;
        }
      case "metadata updated":
        return updateMetadata(node, args);
      case "alive":
        return setStatus(node, ZNode.STATUS_ALIVE);
      case "dead":
        return setStatus(node, ZNode.STATUS_DEAD);
      case "sleep":
        return setStatus(node, ZNode.STATUS_ASLEEP);
      case "wake up":
        return setStatus(node, ZNode.STATUS_AWAKE);
      default:
        return Effect.NONE;
    }
  }

  private Effect applyControllerEvent(String name, Map<String, Object> event) {
    Map<String, Object> nodeState = Json.obj(event, "node");
    if (nodeState == null) {
      nodeState = Json.obj(Json.obj(event, "args"), "node");
    }

    if ("node added".equals(name) && nodeState != null) {
      ZNode node = ZNode.fromState(nodeState);
      if (node.nodeId > 0) {
        nodes.put(node.nodeId, node);
        return Effect.STRUCTURE;
      }
    } else if ("node removed".equals(name) && nodeState != null) {
      int id = Json.integer(nodeState, "nodeId", -1);
      return nodes.remove(id) == null ? Effect.NONE : Effect.STRUCTURE;
    }
    return Effect.NONE;
  }

  private static Effect setStatus(ZNode node, int status) {
    if (node.status == status) {
      return Effect.NONE;
    }
    node.status = status;
    return Effect.NODE_STATUS;
  }

  private static Effect upsertValue(ZNode node, Map<String, Object> args, String valueField) {
    if (args == null) {
      return Effect.NONE;
    }
    ValueId id = ValueId.fromJson(args);
    if (id.commandClass() < 0) {
      return Effect.NONE;
    }

    ZValue value = node.values.get(id.key());
    boolean created = false;
    if (value == null) {
      // A value we were never told about. It still gets a tag, typed from its first value until
      // metadata arrives (usually right behind it).
      value = new ZValue(node.nodeId, id);
      value.type = inferType(args.get(valueField));
      node.values.put(id.key(), value);
      created = true;
    }
    value.applyNames(args);
    if (args.containsKey(valueField)) {
      value.setValue(args.get(valueField));
    }
    return created ? Effect.STRUCTURE : Effect.VALUE;
  }

  private static Effect updateMetadata(ZNode node, Map<String, Object> args) {
    if (args == null) {
      return Effect.NONE;
    }
    ValueId id = ValueId.fromJson(args);
    ZValue value = node.values.get(id.key());
    boolean created = value == null;
    if (created) {
      value = new ZValue(node.nodeId, id);
      node.values.put(id.key(), value);
    }

    // Interviews send metadata repeatedly. Rebuild only when something the tree shows changed.
    String before = created ? null : shapeOf(value);
    value.applyNames(args);
    value.applyMetadata(Json.obj(args, "metadata"));
    if (args.containsKey("value")) {
      value.setValue(args.get("value"));
    }
    return created || !shapeOf(value).equals(before) ? Effect.STRUCTURE : Effect.NONE;
  }

  /** Everything about a value that ends up in the node tree. */
  private static String shapeOf(ZValue v) {
    return v.uaKind() + "|" + v.writeable + "|" + v.describe() + "|" + v.commandClassName + "|"
        + v.propertyName + "|" + v.propertyKeyName;
  }

  private static String inferType(Object v) {
    if (v instanceof Boolean) {
      return "boolean";
    }
    if (v instanceof Number) {
      return "number";
    }
    if (v instanceof String) {
      return "string";
    }
    return "any";
  }

  // ------------------------------------------------------------------ queries

  public ZNode node(int nodeId) {
    return nodes.get(nodeId);
  }

  public ZValue value(int nodeId, String valueKey) {
    ZNode n = nodes.get(nodeId);
    return n == null ? null : n.values.get(valueKey);
  }

  public int nodeCount() {
    return nodes.size();
  }

  public Object serverInfo(String field, boolean connected) {
    return switch (field) {
      case "Connected" -> connected;
      case "DriverVersion" -> driverVersion;
      case "ServerVersion" -> serverVersion;
      case "SchemaVersion" -> (long) schemaVersion;
      case "HomeId" -> String.format("0x%08x", homeId);
      case "ControllerNodeId" -> (long) controllerNodeId;
      case "NodeCount" -> (long) nodes.size();
      default -> null;
    };
  }

  // -------------------------------------------------------------------- tree

  /** What to expose. Built from the device config. */
  public record Options(
      boolean nameNodeFolders,
      Set<String> excludedCommandClasses,
      boolean includeNodeInfo,
      boolean writesEnabled) {

    /** Parses "Configuration, 112, Version" into lower-case names and numbers. */
    public static Set<String> parseExclusions(String text) {
      Set<String> out = new HashSet<>();
      if (text == null) {
        return out;
      }
      for (String part : text.split(",")) {
        String p = part.trim().toLowerCase(Locale.ROOT);
        if (!p.isEmpty()) {
          out.add(p);
        }
      }
      return out;
    }
  }

  /**
   * Lays the network out as tags.
   *
   * <pre>
   * _Server.Connected, _Server.HomeId ...
   * Node_005._Info.Status, Node_005._Info.Name ...
   * Node_005._Info.RefreshValues                       (write true to re-read every value)
   * Node_005.Binary_Switch.currentValue
   * Node_005.Binary_Switch.targetValue
   * Node_005.EP1.Multilevel_Sensor.Air_temperature     (endpoints above 0 get their own folder)
   * Node_005.Notification.Home_Security.Motion_sensor_status   (property keys nest one level)
   * </pre>
   *
   * Folder and tag names are sanitised to letters, digits and underscores, so they are valid
   * Ignition tag names and never contain the dot used as the path separator. Collisions get a
   * numeric suffix.
   */
  public synchronized List<TagSpec> buildTags(Options options) {
    List<TagSpec> specs = new ArrayList<>();

    for (String field : SERVER_FIELDS) {
      specs.add(
          new TagSpec(
              "_Server." + field,
              TagSpec.Kind.SERVER_INFO,
              -1,
              null,
              field,
              kindOfInfo(field),
              false,
              "Z-Wave JS server " + field));
    }

    Set<String> usedNodeFolders = new HashSet<>();
    for (ZNode node : nodes.values()) {
      String folder = nodeFolderName(node, options.nameNodeFolders(), usedNodeFolders);

      if (options.includeNodeInfo()) {
        for (String field : ZNode.INFO_FIELDS) {
          specs.add(
              new TagSpec(
                  folder + "._Info." + field,
                  TagSpec.Kind.NODE_INFO,
                  node.nodeId,
                  null,
                  field,
                  kindOfInfo(field),
                  false,
                  "Node " + node.nodeId + " " + field));
        }
        for (String command : NODE_COMMANDS) {
          specs.add(
              new TagSpec(
                  folder + "._Info." + command,
                  TagSpec.Kind.NODE_COMMAND,
                  node.nodeId,
                  null,
                  command,
                  ZValue.UaKind.BOOLEAN,
                  options.writesEnabled(),
                  "Write true to "
                      + ("Ping".equals(command)
                          ? "ping the node"
                          : "re-read every value from the node")));
        }
      }

      specs.addAll(valueTags(node, folder, options));
    }
    return specs;
  }

  private static ZValue.UaKind kindOfInfo(String field) {
    return switch (field) {
      case "Connected", "Ready" -> ZValue.UaKind.BOOLEAN;
      case "NodeId", "SchemaVersion", "ControllerNodeId", "NodeCount" -> ZValue.UaKind.NUMBER;
      default -> ZValue.UaKind.STRING;
    };
  }

  private static String nodeFolderName(ZNode node, boolean byName, Set<String> used) {
    String base = String.format("Node_%03d", node.nodeId);
    if (byName && node.name != null && !node.name.isBlank()) {
      base = sanitize(node.name);
    }
    String name = base;
    if (!used.add(name)) {
      name = base + "_" + node.nodeId; // two nodes named alike: disambiguate by id, not by order
      used.add(name);
    }
    return name;
  }

  private static List<TagSpec> valueTags(ZNode node, String folder, Options options) {
    List<ZValue> values = new ArrayList<>();
    for (ZValue v : node.values.values()) {
      if (!excluded(v, options.excludedCommandClasses())) {
        values.add(v);
      }
    }

    // First pass: every value's natural path, and the folders those paths imply.
    Map<ZValue, String[]> segmentsByValue = new HashMap<>();
    Set<String> folders = new HashSet<>();
    for (ZValue v : values) {
      String[] segments = segmentsFor(v);
      segmentsByValue.put(v, segments);
      StringBuilder prefix = new StringBuilder(folder);
      for (int i = 0; i < segments.length - 1; i++) {
        prefix.append('.').append(segments[i]);
        folders.add(prefix.toString());
      }
    }

    // Stable order: by path, then by value key, so collision suffixes don't shuffle between runs.
    values.sort(
        Comparator.comparing((ZValue v) -> String.join(".", segmentsByValue.get(v)))
            .thenComparing(v -> v.id.key()));

    // Second pass: claim paths, suffixing anything that collides with a tag or a folder.
    List<TagSpec> specs = new ArrayList<>();
    Set<String> taken = new HashSet<>();
    for (ZValue v : values) {
      String path = folder + "." + String.join(".", segmentsByValue.get(v));
      String unique = path;
      for (int n = 2; folders.contains(unique) || !taken.add(unique); n++) {
        unique = path + "_" + n;
      }

      boolean writable = options.writesEnabled() && v.writeable();
      specs.add(
          new TagSpec(
              unique,
              TagSpec.Kind.VALUE,
              node.nodeId,
              v.id.key(),
              null,
              v.uaKind(),
              writable,
              v.describe()));
    }
    return specs;
  }

  /** endpoint folder (if any), command class, property, property key (if any). */
  static String[] segmentsFor(ZValue v) {
    List<String> segments = new ArrayList<>(4);
    if (v.id.endpoint() > 0) {
      segments.add("EP" + v.id.endpoint());
    }
    String cc = v.commandClassName;
    segments.add(cc == null || cc.isBlank() ? "CC_" + v.id.commandClass() : sanitize(cc));

    String prop = v.propertyName;
    segments.add(sanitize(prop == null || prop.isBlank() ? String.valueOf(v.id.property()) : prop));

    if (v.id.propertyKey() != null) {
      String key = v.propertyKeyName;
      segments.add(
          sanitize(key == null || key.isBlank() ? String.valueOf(v.id.propertyKey()) : key));
    }
    return segments.toArray(new String[0]);
  }

  private static boolean excluded(ZValue v, Set<String> exclusions) {
    if (exclusions.isEmpty()) {
      return false;
    }
    if (exclusions.contains(Integer.toString(v.id.commandClass()))) {
      return true;
    }
    String cc = v.commandClassName;
    return cc != null && exclusions.contains(cc.trim().toLowerCase(Locale.ROOT));
  }

  /** Letters, digits and underscores only; runs of anything else collapse to one underscore. */
  public static String sanitize(String s) {
    if (s == null) {
      return "_";
    }
    StringBuilder sb = new StringBuilder(s.length());
    boolean lastUnderscore = false;
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      boolean ok = (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || (c >= '0' && c <= '9');
      if (ok) {
        sb.append(c);
        lastUnderscore = false;
      } else if (!lastUnderscore) {
        sb.append('_');
        lastUnderscore = true;
      }
    }
    String out = sb.toString();
    int start = 0;
    int end = out.length();
    while (start < end && out.charAt(start) == '_') {
      start++;
    }
    while (end > start && out.charAt(end - 1) == '_') {
      end--;
    }
    out = out.substring(start, end);
    return out.isEmpty() ? "_" : out;
  }

  /** For tests and logging: every value key on a node. */
  public Set<String> valueKeys(int nodeId) {
    ZNode n = nodes.get(nodeId);
    return n == null ? Set.of() : new LinkedHashSet<>(n.values.keySet());
  }
}
