package org.ledgerapi.http;

import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.JsonToken;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.ledgerapi.domain.ErrorCode;
import org.ledgerapi.domain.FieldError;
import org.ledgerapi.domain.LedgerException;

/**
 * A request body read token by token (NFR-API-3, NFR-API-7). The root must be one JSON object; each
 * member keeps its raw token kind so amounts can be checked as integer tokens without any
 * floating-point conversion. Duplicate member names are detected rather than overwritten.
 */
final class JsonBody {
  private static final JsonFactory FACTORY = JsonFactory.builder().build();

  /** One top-level member. {@code text} is the string or number text; null for other kinds. */
  record Member(JsonToken token, String text, boolean fitsLong) {}

  private final Map<String, Member> members;
  private final List<String> duplicates;

  private JsonBody(Map<String, Member> members, List<String> duplicates) {
    this.members = members;
    this.duplicates = duplicates;
  }

  static JsonBody empty() {
    return new JsonBody(new LinkedHashMap<>(), List.of());
  }

  /** Parses {@code bytes}; syntax errors and a non-object root are {@code VALIDATION_ERROR}. */
  static JsonBody parse(byte[] bytes) {
    Map<String, Member> members = new LinkedHashMap<>();
    List<String> duplicates = new ArrayList<>();
    try (JsonParser parser = FACTORY.createParser(bytes)) {
      JsonToken root = parser.nextToken();
      if (root == null) {
        throw invalid("body", "json");
      }
      if (root != JsonToken.START_OBJECT) {
        parser.skipChildren();
        drain(parser);
        throw invalid("body", "object");
      }
      JsonToken token;
      while ((token = parser.nextToken()) == JsonToken.FIELD_NAME) {
        String name = parser.currentName();
        JsonToken value = parser.nextToken();
        Member member = read(parser, value);
        if (members.containsKey(name)) {
          if (!duplicates.contains(name)) {
            duplicates.add(name);
          }
        } else {
          members.put(name, member);
        }
      }
      if (token != JsonToken.END_OBJECT) {
        throw invalid("body", "json");
      }
      drain(parser);
    } catch (JsonProcessingException e) {
      throw invalid("body", "json");
    } catch (IOException e) {
      throw invalid("body", "json");
    }
    return new JsonBody(members, duplicates);
  }

  private static Member read(JsonParser parser, JsonToken value) throws IOException {
    if (value == null) {
      throw invalid("body", "json");
    }
    return switch (value) {
      case START_OBJECT, START_ARRAY -> {
        parser.skipChildren();
        yield new Member(value, null, false);
      }
      case VALUE_STRING -> new Member(value, parser.getText(), false);
      case VALUE_NUMBER_INT -> {
        JsonParser.NumberType type = parser.getNumberType();
        boolean fits = type == JsonParser.NumberType.INT || type == JsonParser.NumberType.LONG;
        yield new Member(value, parser.getText(), fits);
      }
      default -> new Member(value, parser.getText(), false);
    };
  }

  /** Rejects any content after the root value. */
  private static void drain(JsonParser parser) throws IOException {
    if (parser.nextToken() != null) {
      throw invalid("body", "json");
    }
  }

  static LedgerException invalid(String field, String rule) {
    return new LedgerException(
        ErrorCode.VALIDATION_ERROR, "Invalid request", List.of(new FieldError(field, rule)));
  }

  List<String> duplicates() {
    return duplicates;
  }

  Iterable<String> names() {
    return members.keySet();
  }

  Optional<Member> get(String name) {
    return Optional.ofNullable(members.get(name));
  }
}
