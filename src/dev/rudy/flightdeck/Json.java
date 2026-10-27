package dev.rudy.flightdeck;

import java.util.*;

/** Bounded JSON emission; no input JSON parser or third-party serializer. */
final class Json {
  static final int MAX_CHARS = 16 * 1024 * 1024;
  private final StringBuilder text = new StringBuilder();
  private void add(String value) {
    if (value.length() > MAX_CHARS - text.length()) throw new IllegalArgumentException("limit: report exceeds 16 Mi characters");
    text.append(value);
  }
  private void string(String value) {
    add("\"");
    for (int i = 0; i < value.length(); i++) {
      char c = value.charAt(i);
      switch (c) {
        case '"' -> add("\\\""); case '\\' -> add("\\\\");
        case '\n' -> add("\\n"); case '\r' -> add("\\r"); case '\t' -> add("\\t");
        case '<' -> add("\\u003c"); case '>' -> add("\\u003e"); case '&' -> add("\\u0026");
        default -> {
          if (c < 0x20 || Character.isSurrogate(c) || c == '\u2028' || c == '\u2029')
            add(String.format(Locale.ROOT, "\\u%04x", (int)c));
          else add(String.valueOf(c));
        }
      }
    }
    add("\"");
  }
  private void value(Object value, int depth) {
    if (depth > 64) throw new IllegalArgumentException("limit: report nesting exceeds 64");
    if (value == null) add("null");
    else if (value instanceof String string) string(string);
    else if (value instanceof Boolean || value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) add(value.toString());
    else if (value instanceof Double number) { if (!Double.isFinite(number)) throw new IllegalArgumentException("Non-finite report number"); add(number.toString()); }
    else if (value instanceof Map<?, ?> map) {
      add("{"); boolean first = true;
      for (var entry : map.entrySet()) {
        if (!(entry.getKey() instanceof String key)) throw new IllegalArgumentException("Non-string report key");
        if (!first) add(","); first = false; string(key); add(":"); value(entry.getValue(), depth + 1);
      }
      add("}");
    } else if (value instanceof Iterable<?> values) {
      add("["); boolean first = true;
      for (Object item : values) { if (!first) add(","); first = false; value(item, depth + 1); }
      add("]");
    } else throw new IllegalArgumentException("Unsupported report type: " + value.getClass().getName());
  }
  static String write(Object value) { Json json = new Json(); json.value(value, 0); return json.text.toString(); }
  private Json() {}
}
