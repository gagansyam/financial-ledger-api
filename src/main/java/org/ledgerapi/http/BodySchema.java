package org.ledgerapi.http;

import com.fasterxml.jackson.core.JsonToken;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.ledgerapi.domain.CurrencyCode;
import org.ledgerapi.domain.ErrorCode;
import org.ledgerapi.domain.FieldError;
import org.ledgerapi.domain.Ids;
import org.ledgerapi.domain.LedgerException;
import org.ledgerapi.domain.LedgerRules;
import org.ledgerapi.domain.TextRules;

/**
 * Field rules of one request body, in documented schema order (SRS 4.4). {@link #validate} applies
 * the structural rules; the typed accessors then check values in the caller's order.
 */
final class BodySchema {
  enum Kind {
    /** Required, non-null JSON string. */
    STRING,
    /** Optional JSON string or null; a missing member reads as null. */
    OPTIONAL_STRING,
    /** Required amount; any present token is checked by {@link Validated#amount}. */
    AMOUNT
  }

  private final Map<String, Kind> fields = new LinkedHashMap<>();

  static BodySchema of() {
    return new BodySchema();
  }

  BodySchema field(String name, Kind kind) {
    fields.put(name, kind);
    return this;
  }

  /**
   * Parses and structurally validates a body: JSON syntax, duplicate members, unknown members,
   * missing required members, then member types. An empty body is accepted only when {@code
   * optional} (FR-4.2).
   */
  Validated validate(byte[] body, boolean optional) {
    JsonBody json;
    if (body.length == 0) {
      if (!optional) {
        throw JsonBody.invalid("body", "required");
      }
      json = JsonBody.empty();
    } else {
      json = JsonBody.parse(body);
    }
    if (!json.duplicates().isEmpty()) {
      throw failures(json.duplicates(), "duplicate");
    }
    List<String> unknown = new ArrayList<>();
    for (String name : json.names()) {
      if (!fields.containsKey(name)) {
        unknown.add(name);
      }
    }
    if (!unknown.isEmpty()) {
      throw failures(unknown, "unknown");
    }
    List<String> missing = new ArrayList<>();
    for (Map.Entry<String, Kind> f : fields.entrySet()) {
      if (f.getValue() != Kind.OPTIONAL_STRING && json.get(f.getKey()).isEmpty()) {
        missing.add(f.getKey());
      }
    }
    if (!missing.isEmpty()) {
      throw failures(missing, "required");
    }
    List<String> wrongType = new ArrayList<>();
    for (Map.Entry<String, Kind> f : fields.entrySet()) {
      JsonToken token = json.get(f.getKey()).map(JsonBody.Member::token).orElse(null);
      boolean ok =
          switch (f.getValue()) {
            case STRING -> token == JsonToken.VALUE_STRING;
            case OPTIONAL_STRING ->
                token == null || token == JsonToken.VALUE_STRING || token == JsonToken.VALUE_NULL;
            case AMOUNT -> true;
          };
      if (!ok) {
        wrongType.add(f.getKey());
      }
    }
    if (!wrongType.isEmpty()) {
      throw failures(wrongType, "type");
    }
    return new Validated(json);
  }

  private static LedgerException failures(List<String> names, String rule) {
    List<FieldError> details = new ArrayList<>();
    for (String name : names) {
      details.add(new FieldError(name, rule));
    }
    return new LedgerException(ErrorCode.VALIDATION_ERROR, "Invalid request", details);
  }

  /** A structurally valid body with typed, value-checking accessors. */
  static final class Validated {
    private final JsonBody json;

    private Validated(JsonBody json) {
      this.json = json;
    }

    /** The raw string of a STRING or OPTIONAL_STRING member; null when missing or null. */
    String string(String name) {
      return json.get(name)
          .filter(m -> m.token() == JsonToken.VALUE_STRING)
          .map(JsonBody.Member::text)
          .orElse(null);
    }

    String uuid(String name) {
      return Ids.require(name, string(name));
    }

    /** JSON integer token in 1..10^12 (NFR-API-3, NFR-SEC-2). */
    long amount(String name) {
      JsonBody.Member member = json.get(name).orElseThrow();
      if (member.token() != JsonToken.VALUE_NUMBER_INT || !member.fitsLong()) {
        throw invalidAmount(name, "integer");
      }
      long value = Long.parseLong(member.text());
      if (!LedgerRules.isValidAmount(value)) {
        throw invalidAmount(name, "range");
      }
      return value;
    }

    String currency(String name) {
      String value = string(name);
      if (!CurrencyCode.isWellFormed(value)) {
        throw JsonBody.invalid(name, "format");
      }
      return value;
    }

    /** Required text of {@code min..max} scalar values without control characters. */
    String text(String name, int min, int max) {
      String value = string(name);
      checkText(name, value, min, max);
      return value;
    }

    /** Optional text; null stays null and an empty string stays empty (SRS 4.5). */
    String optionalText(String name, int max) {
      String value = string(name);
      if (value != null) {
        checkText(name, value, 0, max);
      }
      return value;
    }

    String matching(String name, java.util.regex.Pattern pattern) {
      String value = string(name);
      if (!pattern.matcher(value).matches()) {
        throw JsonBody.invalid(name, "format");
      }
      return value;
    }

    private static void checkText(String name, String value, int min, int max) {
      if (!TextRules.isWellFormed(value)) {
        throw JsonBody.invalid(name, "encoding");
      }
      if (TextRules.hasControlCharacter(value)) {
        throw JsonBody.invalid(name, "control");
      }
      int length = TextRules.scalarLength(value);
      if (length < min || length > max) {
        throw JsonBody.invalid(name, "length");
      }
    }

    private static LedgerException invalidAmount(String name, String rule) {
      return new LedgerException(
          ErrorCode.INVALID_AMOUNT,
          "Amount must be an integer number of minor units in the allowed range",
          List.of(new FieldError(name, rule)));
    }
  }
}
