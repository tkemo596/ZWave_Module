package com.arringtonautomation.ignition.zwave.zwjs;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A minimal JSON reader and writer for the Z-Wave JS server protocol.
 *
 * <p>Deliberately not a JSON library: the module ships no
 * third-party dependencies, and the gateway already carries several JSON implementations whose
 * versions we do not want to fight.
 *
 * <p>Parsed shapes: objects become {@code LinkedHashMap<String, Object>} (insertion order kept),
 * arrays become {@code ArrayList<Object>}, integral numbers that fit become {@code Long}, every other
 * number becomes {@code Double}, plus {@code String}, {@code Boolean} and {@code null}. The writer
 * accepts the same shapes, and any other {@code Number}.
 */
public final class Json {

  private Json() {}

  // ------------------------------------------------------------------ reading

  public static Object parse(String text) {
    Parser p = new Parser(text);
    p.skipWhitespace();
    Object value = p.readValue();
    p.skipWhitespace();
    if (p.pos != text.length()) {
      throw p.error("Unexpected trailing content");
    }
    return value;
  }

  @SuppressWarnings("unchecked")
  public static Map<String, Object> parseObject(String text) {
    Object value = parse(text);
    if (value instanceof Map<?, ?> m) {
      return (Map<String, Object>) m;
    }
    throw new IllegalArgumentException("Expected a JSON object");
  }

  private static final class Parser {
    private final String s;
    private int pos;

    Parser(String s) {
      this.s = s;
    }

    IllegalArgumentException error(String message) {
      return new IllegalArgumentException(message + " at offset " + pos);
    }

    void skipWhitespace() {
      while (pos < s.length()) {
        char c = s.charAt(pos);
        if (c == ' ' || c == '\t' || c == '\n' || c == '\r') {
          pos++;
        } else {
          break;
        }
      }
    }

    Object readValue() {
      if (pos >= s.length()) {
        throw error("Unexpected end of input");
      }
      char c = s.charAt(pos);
      switch (c) {
        case '{':
          return readObject();
        case '[':
          return readArray();
        case '"':
          return readString();
        case 't':
          expect("true");
          return Boolean.TRUE;
        case 'f':
          expect("false");
          return Boolean.FALSE;
        case 'n':
          expect("null");
          return null;
        default:
          if (c == '-' || (c >= '0' && c <= '9')) {
            return readNumber();
          }
          throw error("Unexpected character '" + c + "'");
      }
    }

    void expect(String word) {
      if (!s.startsWith(word, pos)) {
        throw error("Expected " + word);
      }
      pos += word.length();
    }

    Map<String, Object> readObject() {
      Map<String, Object> map = new LinkedHashMap<>();
      pos++; // {
      skipWhitespace();
      if (pos < s.length() && s.charAt(pos) == '}') {
        pos++;
        return map;
      }
      while (true) {
        skipWhitespace();
        if (pos >= s.length() || s.charAt(pos) != '"') {
          throw error("Expected a string key");
        }
        String key = readString();
        skipWhitespace();
        if (pos >= s.length() || s.charAt(pos) != ':') {
          throw error("Expected ':'");
        }
        pos++;
        skipWhitespace();
        map.put(key, readValue());
        skipWhitespace();
        if (pos >= s.length()) {
          throw error("Unterminated object");
        }
        char c = s.charAt(pos++);
        if (c == '}') {
          return map;
        }
        if (c != ',') {
          throw error("Expected ',' or '}'");
        }
      }
    }

    List<Object> readArray() {
      List<Object> list = new ArrayList<>();
      pos++; // [
      skipWhitespace();
      if (pos < s.length() && s.charAt(pos) == ']') {
        pos++;
        return list;
      }
      while (true) {
        skipWhitespace();
        list.add(readValue());
        skipWhitespace();
        if (pos >= s.length()) {
          throw error("Unterminated array");
        }
        char c = s.charAt(pos++);
        if (c == ']') {
          return list;
        }
        if (c != ',') {
          throw error("Expected ',' or ']'");
        }
      }
    }

    String readString() {
      pos++; // opening quote
      StringBuilder sb = new StringBuilder();
      while (true) {
        if (pos >= s.length()) {
          throw error("Unterminated string");
        }
        char c = s.charAt(pos++);
        if (c == '"') {
          return sb.toString();
        }
        if (c != '\\') {
          sb.append(c);
          continue;
        }
        if (pos >= s.length()) {
          throw error("Unterminated escape");
        }
        char e = s.charAt(pos++);
        switch (e) {
          case '"' -> sb.append('"');
          case '\\' -> sb.append('\\');
          case '/' -> sb.append('/');
          case 'b' -> sb.append('\b');
          case 'f' -> sb.append('\f');
          case 'n' -> sb.append('\n');
          case 'r' -> sb.append('\r');
          case 't' -> sb.append('\t');
          case 'u' -> {
            if (pos + 4 > s.length()) {
              throw error("Truncated \\u escape");
            }
            sb.append((char) Integer.parseInt(s.substring(pos, pos + 4), 16));
            pos += 4;
          }
          default -> throw error("Bad escape \\" + e);
        }
      }
    }

    Object readNumber() {
      int start = pos;
      if (s.charAt(pos) == '-') {
        pos++;
      }
      boolean integral = true;
      while (pos < s.length()) {
        char c = s.charAt(pos);
        if (c >= '0' && c <= '9') {
          pos++;
        } else if (c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
          integral = false;
          pos++;
        } else {
          break;
        }
      }
      String num = s.substring(start, pos);
      if (integral) {
        try {
          return Long.parseLong(num);
        } catch (NumberFormatException tooBig) {
          // falls through to double
        }
      }
      try {
        return Double.parseDouble(num);
      } catch (NumberFormatException e) {
        throw error("Bad number '" + num + "'");
      }
    }
  }

  // ------------------------------------------------------------------ writing

  public static String write(Object value) {
    StringBuilder sb = new StringBuilder(128);
    writeValue(sb, value);
    return sb.toString();
  }

  private static void writeValue(StringBuilder sb, Object value) {
    if (value == null) {
      sb.append("null");
    } else if (value instanceof Map<?, ?> m) {
      sb.append('{');
      boolean first = true;
      for (Map.Entry<?, ?> e : m.entrySet()) {
        if (!first) {
          sb.append(',');
        }
        first = false;
        writeString(sb, String.valueOf(e.getKey()));
        sb.append(':');
        writeValue(sb, e.getValue());
      }
      sb.append('}');
    } else if (value instanceof List<?> list) {
      sb.append('[');
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) {
          sb.append(',');
        }
        writeValue(sb, list.get(i));
      }
      sb.append(']');
    } else if (value instanceof Boolean b) {
      sb.append(b.booleanValue());
    } else if (value instanceof Double d) {
      // NaN and the infinities are not valid JSON.
      if (d.isNaN() || d.isInfinite()) {
        sb.append("null");
      } else if (d == Math.rint(d) && Math.abs(d) < 1e15) {
        sb.append(d.longValue()); // 50.0 -> 50: Z-Wave JS validates integer-typed values
      } else {
        sb.append(d);
      }
    } else if (value instanceof Float f) {
      writeValue(sb, f.doubleValue());
    } else if (value instanceof Number n) {
      sb.append(n);
    } else {
      writeString(sb, String.valueOf(value));
    }
  }

  private static void writeString(StringBuilder sb, String s) {
    sb.append('"');
    for (int i = 0; i < s.length(); i++) {
      char c = s.charAt(i);
      switch (c) {
        case '"' -> sb.append("\\\"");
        case '\\' -> sb.append("\\\\");
        case '\n' -> sb.append("\\n");
        case '\r' -> sb.append("\\r");
        case '\t' -> sb.append("\\t");
        case '\b' -> sb.append("\\b");
        case '\f' -> sb.append("\\f");
        default -> {
          if (c < 0x20) {
            sb.append(String.format("\\u%04x", (int) c));
          } else {
            sb.append(c);
          }
        }
      }
    }
    sb.append('"');
  }

  // ------------------------------------------------------------------ helpers

  /** Typed accessors over parsed maps, tolerant of absent keys and numeric widening. */
  @SuppressWarnings("unchecked")
  public static Map<String, Object> obj(Map<String, Object> m, String key) {
    Object v = m == null ? null : m.get(key);
    return v instanceof Map<?, ?> ? (Map<String, Object>) v : null;
  }

  @SuppressWarnings("unchecked")
  public static List<Object> list(Map<String, Object> m, String key) {
    Object v = m == null ? null : m.get(key);
    return v instanceof List<?> ? (List<Object>) v : null;
  }

  public static String str(Map<String, Object> m, String key) {
    Object v = m == null ? null : m.get(key);
    return v == null ? null : String.valueOf(v);
  }

  public static int integer(Map<String, Object> m, String key, int fallback) {
    Object v = m == null ? null : m.get(key);
    return v instanceof Number n ? n.intValue() : fallback;
  }

  public static boolean bool(Map<String, Object> m, String key, boolean fallback) {
    Object v = m == null ? null : m.get(key);
    return v instanceof Boolean b ? b : fallback;
  }
}
