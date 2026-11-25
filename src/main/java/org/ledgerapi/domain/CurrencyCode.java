package org.ledgerapi.domain;

import java.util.List;
import java.util.regex.Pattern;

/**
 * Validated 3-letter uppercase ISO 4217 currency code (BR-2, DC-5, ADR-0006).
 *
 * <p>Does not use floating-point values; minor-unit exponents are tracked separately in currency
 * configuration and storage.
 */
public record CurrencyCode(String code) {
  private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Z]{3}$");

  public CurrencyCode {
    if (!isWellFormed(code)) {
      throw new LedgerException(
          ErrorCode.VALIDATION_ERROR,
          "Currency code must be a 3-letter uppercase ISO 4217 code.",
          List.of(new FieldError("currency", "Must match ^[A-Z]{3}$")));
    }
  }

  /** Returns whether {@code value} is a well-formed code, without throwing. */
  public static boolean isWellFormed(String value) {
    return value != null && CODE_PATTERN.matcher(value).matches();
  }

  public static CurrencyCode of(String raw) {
    return new CurrencyCode(raw);
  }

  @Override
  public String toString() {
    return code;
  }
}
