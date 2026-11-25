package org.ledgerapi.domain;

import java.util.List;
import java.util.regex.Pattern;

/** Identifier contract: lowercase, hyphenated UUID version 4 (NFR-API-2). */
public final class Ids {
  private static final Pattern UUID_V4 =
      Pattern.compile("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");

  private Ids() {}

  public static boolean isValid(String value) {
    return value != null && UUID_V4.matcher(value).matches();
  }

  /** Returns {@code value} or throws {@code MALFORMED_ID} naming {@code field}. */
  public static String require(String field, String value) {
    if (!isValid(value)) {
      throw new LedgerException(
          ErrorCode.MALFORMED_ID, "Malformed identifier", List.of(new FieldError(field, "uuid")));
    }
    return value;
  }
}
