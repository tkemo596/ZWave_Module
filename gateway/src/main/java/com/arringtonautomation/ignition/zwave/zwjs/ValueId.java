package com.arringtonautomation.ignition.zwave.zwjs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Identifies one Z-Wave JS value on a node: command class, endpoint, property and optional property
 * key.
 *
 * <p>{@code property} and {@code propertyKey} are kept with their original JSON types. They are
 * strings for most command classes, but numbers for some - Configuration CC parameters are
 * {@code property: 3}, and Meter CC keys are numbers like {@code 65537}. Z-Wave JS matches value
 * ids by type, so {@code "3"} is not {@code 3}; sending back the wrong one fails with "value not
 * found".
 */
public record ValueId(int commandClass, int endpoint, Object property, Object propertyKey) {

  public ValueId {
    property = normalise(property);
    propertyKey = normalise(propertyKey);
  }

  /** Long and Double from the parser; keep integral numbers as Long so equals() is stable. */
  private static Object normalise(Object o) {
    if (o instanceof Double d && d == Math.rint(d)) {
      return d.longValue();
    }
    if (o instanceof Integer i) {
      return i.longValue();
    }
    return o;
  }

  public static ValueId fromJson(Map<String, Object> m) {
    return new ValueId(
        Json.integer(m, "commandClass", -1),
        Json.integer(m, "endpoint", 0),
        m.get("property"),
        m.get("propertyKey"));
  }

  /** The {@code valueId} object node.set_value expects. propertyKey is omitted when absent. */
  public Map<String, Object> toJson() {
    Map<String, Object> m = new LinkedHashMap<>();
    m.put("commandClass", commandClass);
    m.put("endpoint", endpoint);
    m.put("property", property);
    if (propertyKey != null) {
      m.put("propertyKey", propertyKey);
    }
    return m;
  }

  /** A stable string key, type-tagged so 3 and "3" stay distinct. */
  public String key() {
    return commandClass + ":" + endpoint + ":" + tag(property) + ":" + tag(propertyKey);
  }

  private static String tag(Object o) {
    if (o == null) {
      return "-";
    }
    return (o instanceof Number ? "n" : "s") + o;
  }

  @Override
  public boolean equals(Object o) {
    return o instanceof ValueId v && key().equals(v.key());
  }

  @Override
  public int hashCode() {
    return Objects.hash(key());
  }
}
