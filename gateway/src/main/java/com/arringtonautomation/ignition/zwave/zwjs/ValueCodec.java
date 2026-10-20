package com.arringtonautomation.ignition.zwave.zwjs;

import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Converts between Z-Wave JS values and the Java objects an OPC UA Variant carries.
 *
 * <p>Pure Java so the self-check can exercise it. The device wraps the results in Variants.
 */
public final class ValueCodec {

  private ValueCodec() {}

  /**
   * SetValueStatus codes that mean the write did not happen. 1 (Working) means accepted and in
   * progress - Supervision reports the outcome later as a value update - so it counts as success,
   * as do 254 (SuccessUnsupervised) and 255 (Success).
   */
  private static final Map<Integer, String> FAILED_STATUS =
      Map.of(
          0, "the device does not support this value",
          2, "the device reported failure",
          3, "endpoint not found",
          4, "not implemented by the device",
          5, "invalid value");

  // ------------------------------------------------------------- Z-Wave -> UA

  /** The object to put in a Variant for a value of this kind; null for no value. */
  public static Object toUa(ZValue.UaKind kind, Object value) {
    if (value == null) {
      return null;
    }
    return switch (kind) {
      case NUMBER -> {
        if (value instanceof Number n) {
          yield n.doubleValue();
        }
        if (value instanceof Boolean b) {
          yield b ? 1.0 : 0.0;
        }
        try {
          yield Double.parseDouble(value.toString().trim());
        } catch (NumberFormatException e) {
          yield null; // e.g. "unknown" from a device that misreports its own metadata
        }
      }
      case BOOLEAN -> toBoolean(value);
      case STRING -> value instanceof String s ? s : Json.write(value);
      case JSON -> value instanceof String s ? s : Json.write(value);
    };
  }

  /** Lenient boolean: true/false, numbers (non-zero is true) and on/off/yes/no strings. */
  public static Boolean toBoolean(Object value) {
    if (value == null) {
      return null;
    }
    if (value instanceof Boolean b) {
      return b;
    }
    if (value instanceof Number n) {
      return n.doubleValue() != 0;
    }
    String s = value.toString().trim().toLowerCase(Locale.ROOT);
    if (Set.of("true", "1", "on", "yes").contains(s)) {
      return true;
    }
    if (Set.of("false", "0", "off", "no").contains(s)) {
      return false;
    }
    return null;
  }

  // ------------------------------------------------------------- UA -> Z-Wave

  /**
   * The JSON value to send in node.set_value for a write of {@code raw} to {@code target}.
   *
   * @throws IllegalArgumentException if {@code raw} cannot be expressed as the target's type.
   */
  public static Object toZwave(ZValue target, Object raw) {
    if (raw == null) {
      throw new IllegalArgumentException("cannot write a null value");
    }

    switch (target.type()) {
      case "number":
        {
          double d;
          if (raw instanceof Number n) {
            d = n.doubleValue();
          } else if (raw instanceof Boolean b) {
            d = b ? 1 : 0;
          } else {
            try {
              d = Double.parseDouble(raw.toString().trim());
            } catch (NumberFormatException e) {
              throw new IllegalArgumentException("'" + raw + "' is not a number");
            }
          }
          if (Double.isNaN(d) || Double.isInfinite(d)) {
            throw new IllegalArgumentException("not a finite number");
          }
          // Ignition writes Doubles. Z-Wave JS rejects 50.0 where an integer is expected on some
          // command classes, so whole numbers travel as integers.
          if (d == Math.rint(d) && Math.abs(d) < 1e15) {
            return (long) d;
          }
          return d;
        }

      case "boolean":
        {
          Boolean b = toBoolean(raw);
          if (b == null) {
            throw new IllegalArgumentException("'" + raw + "' is not a boolean");
          }
          return b;
        }

      case "string":
        return raw.toString();

      default:
        // duration, color, buffer, arrays, any: the tag carries JSON text. Parse it back so the
        // server receives an object, not a string that happens to contain one. Plain text that is
        // not JSON goes through unchanged.
        if (raw instanceof String s) {
          String t = s.trim();
          if (!t.isEmpty()) {
            try {
              return Json.parse(t);
            } catch (IllegalArgumentException notJson) {
              return s;
            }
          }
          return s;
        }
        if (raw instanceof Double d && d == Math.rint(d)) {
          return d.longValue();
        }
        return raw;
    }
  }

  /**
   * Interprets the result object of node.set_value.
   *
   * <p>Older servers answer {@code {"success": true|false}}; newer schemas answer
   * {@code {"result": {"status": n, "message": "..."}}}. Accepts both.
   *
   * @return null on success, otherwise a reason.
   */
  public static String setValueFailure(Map<String, Object> result) {
    if (result == null) {
      return null;
    }
    Object success = result.get("success");
    if (success instanceof Boolean b && !b) {
      return "the server rejected the value";
    }
    Map<String, Object> inner = Json.obj(result, "result");
    if (inner == null && result.get("status") instanceof Number) {
      inner = result;
    }
    if (inner != null && inner.get("status") instanceof Number n) {
      String reason = FAILED_STATUS.get(n.intValue());
      if (reason != null) {
        String msg = Json.str(inner, "message");
        return msg == null || msg.isBlank() ? reason : reason + " (" + msg + ")";
      }
    }
    return null;
  }
}
