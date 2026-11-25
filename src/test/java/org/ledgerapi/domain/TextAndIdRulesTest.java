package org.ledgerapi.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Text, currency and identifier rules (NFR-API-2, SRS 4.5). */
class TextAndIdRulesTest {
  @Test
  void scalarLengthCountsCodePoints() {
    assertThat(TextRules.scalarLength("a😀")).isEqualTo(2);
    assertThat(TextRules.isPlainText("😀".repeat(140), 0, 140)).isTrue();
    assertThat(TextRules.isPlainText("x".repeat(141), 0, 140)).isFalse();
  }

  @Test
  void loneSurrogatesAndControlsAreRejected() {
    assertThat(TextRules.isWellFormed("a\uD800b")).isFalse();
    assertThat(TextRules.isWellFormed("a\uDC00")).isFalse();
    assertThat(TextRules.hasControlCharacter("tab\t")).isTrue();
    assertThat(TextRules.hasControlCharacter("\u0085")).isTrue();
    assertThat(TextRules.isPlainText("plain", 1, 10)).isTrue();
    assertThat(TextRules.isPlainText("", 1, 10)).isFalse();
  }

  @Test
  void currencyFormat() {
    assertThat(CurrencyCode.isWellFormed("INR")).isTrue();
    assertThat(CurrencyCode.isWellFormed("inr")).isFalse();
    assertThat(CurrencyCode.isWellFormed("INRR")).isFalse();
    assertThat(CurrencyCode.isWellFormed(null)).isFalse();
  }

  @Test
  void uuidVersionFourLowercaseOnly() {
    assertThat(Ids.isValid("00000000-0000-4000-8000-000000000001")).isTrue();
    assertThat(Ids.isValid("00000000-0000-1000-8000-000000000001")).isFalse();
    assertThat(Ids.isValid("00000000-0000-4000-c000-000000000001")).isFalse();
    assertThat(Ids.isValid("ABCDEF00-0000-4000-8000-000000000001")).isFalse();
    assertThat(Ids.isValid(null)).isFalse();
    assertThatThrownBy(() -> Ids.require("walletId", "x"))
        .isInstanceOfSatisfying(
            LedgerException.class,
            e -> {
              assertThat(e.code()).isEqualTo(ErrorCode.MALFORMED_ID);
              assertThat(e.details()).containsExactly(new FieldError("walletId", "uuid"));
            });
  }

  @Test
  void principalNamespacesSeparateUsersAndAdmins() {
    assertThat(Principal.user("u1", "s1").idempotencyPrincipal()).isEqualTo("user:u1");
    assertThat(Principal.admin("u1").idempotencyPrincipal()).isEqualTo("admin:u1");
    assertThat(Principal.admin("ops").isAdmin()).isTrue();
  }
}
