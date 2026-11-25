package org.ledgerapi.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * JSON text for hashing and stored responses (ADR-0004).
 *
 * <p>Supported values are {@code null}, strings, {@code Long}/{@code Integer}, string-keyed maps
 * and lists. {@link #canonical} sorts object members by key and is the input of every fingerprint
 * and transaction hash; {@link #write} keeps map iteration order and is used for response bodies.
 * Neither emits whitespace, and both escape strings identically.
 */
public final class CanonicalJson {
  private CanonicalJson() {}

  /** Canonical form: members sorted by key (UTF-16 code-unit order), no whitespace. */
  public static String canonical(Object value) {
    StringBuilder out = new StringBuilder();
    append(out, value, true);
    return out.toString();
  }

  /** Same encoding as {@link #canonical} but keeps the iteration order of every map. */
  public static String write(Object value) {
    StringBuilder out = new StringBuilder();
    append(out, value, false);
    return out.toString();
  }

  private static void append(StringBuilder out, Object value, boolean sorted) {
    if (value == null) {
      out.append("null");
    } else if (value instanceof String text) {
      appendString(out, text);
    } else if (value instanceof Long || value instanceof Integer) {
      out.append(value.toString());
    } else if (value instanceof Map<?, ?> map) {
      appendObject(out, map, sorted);
    } else if (value instanceof List<?> list) {
      out.append('[');
      for (int i = 0; i < list.size(); i++) {
        if (i > 0) {
          out.append(',');
        }
        append(out, list.get(i), sorted);
      }
      out.append(']');
    } else {
      throw new IllegalArgumentException(
          "Unsupported canonical JSON value type " + value.getClass().getName());
    }
  }

  private static void appendObject(StringBuilder out, Map<?, ?> map, boolean sorted) {
    List<String> keys = new ArrayList<>();
    for (Object key : map.keySet()) {
      if (!(key instanceof String name)) {
        throw new IllegalArgumentException("Object keys must be strings");
      }
      keys.add(name);
    }
    if (sorted) {
      keys.sort(null);
    }
    out.append('{');
    boolean first = true;
    for (String key : keys) {
      if (!first) {
        out.append(',');
      }
      first = false;
      appendString(out, key);
      out.append(':');
      append(out, map.get(key), sorted);
    }
    out.append('}');
  }

  private static void appendString(StringBuilder out, String text) {
    out.append('"');
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      switch (c) {
        case '"' -> out.append("\\\"");
        case '\\' -> out.append("\\\\");
        case '\b' -> out.append("\\b");
        case '\t' -> out.append("\\t");
        case '\n' -> out.append("\\n");
        case '\f' -> out.append("\\f");
        case '\r' -> out.append("\\r");
        default -> {
          if (c < 0x20) {
            out.append(String.format("\\u%04x", (int) c));
          } else {
            out.append(c);
          }
        }
      }
    }
    out.append('"');
  }
}
