package org.ledgerapi.domain;

/** Length and character rules for free text measured in Unicode scalar values (FR-1.1, FR-2.1). */
public final class TextRules {
  private TextRules() {}

  /**
   * True when {@code text} contains no unpaired surrogate, so every code point is a scalar value.
   */
  public static boolean isWellFormed(String text) {
    for (int i = 0; i < text.length(); i++) {
      char c = text.charAt(i);
      if (Character.isHighSurrogate(c)) {
        if (i + 1 >= text.length() || !Character.isLowSurrogate(text.charAt(i + 1))) {
          return false;
        }
        i++;
      } else if (Character.isLowSurrogate(c)) {
        return false;
      }
    }
    return true;
  }

  /** Number of Unicode scalar values; only meaningful for well-formed text. */
  public static int scalarLength(String text) {
    return text.codePointCount(0, text.length());
  }

  /** True when the text contains a Unicode control character (general category Cc). */
  public static boolean hasControlCharacter(String text) {
    return text.codePoints().anyMatch(cp -> Character.getType(cp) == Character.CONTROL);
  }

  /** Well-formed text of {@code min..max} scalar values without control characters. */
  public static boolean isPlainText(String text, int min, int max) {
    if (!isWellFormed(text)) {
      return false;
    }
    int length = scalarLength(text);
    return length >= min && length <= max && !hasControlCharacter(text);
  }
}
