package org.ledgerapi.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;
import org.ledgerapi.domain.ErrorCode;
import org.ledgerapi.domain.LedgerException;
import org.ledgerapi.http.BodySchema.Kind;

/** Token-level body parsing (NFR-API-3, NFR-API-7). */
class BodySchemaTest {
  private static final BodySchema FUNDING =
      BodySchema.of().field("amount", Kind.AMOUNT).field("currency", Kind.STRING);

  private static BodySchema.Validated parse(String json) {
    return FUNDING.validate(json.getBytes(StandardCharsets.UTF_8), false);
  }

  private static String rule(Runnable r) {
    try {
      r.run();
    } catch (LedgerException e) {
      return e.code()
          + ":"
          + (e.details().isEmpty()
              ? ""
              : e.details().get(0).field() + "/" + e.details().get(0).rule());
    }
    return "ok";
  }

  @Test
  void acceptsIntegerTokens() {
    assertThat(parse("{\"amount\":1000000000000,\"currency\":\"INR\"}").amount("amount"))
        .isEqualTo(1_000_000_000_000L);
  }

  @Test
  void ordersStructuralFailures() {
    assertThat(rule(() -> parse("{\"amount\":1,\"amount\":2,\"x\":1}")))
        .isEqualTo("VALIDATION_ERROR:amount/duplicate");
    assertThat(rule(() -> parse("{\"x\":1}"))).isEqualTo("VALIDATION_ERROR:x/unknown");
    assertThat(rule(() -> parse("{\"amount\":1}"))).isEqualTo("VALIDATION_ERROR:currency/required");
    assertThat(rule(() -> parse("{\"amount\":1,\"currency\":1}")))
        .isEqualTo("VALIDATION_ERROR:currency/type");
    assertThat(rule(() -> parse(""))).isEqualTo("VALIDATION_ERROR:body/required");
    assertThat(rule(() -> parse("   "))).isEqualTo("VALIDATION_ERROR:body/json");
    assertThat(rule(() -> parse("\"text\""))).isEqualTo("VALIDATION_ERROR:body/object");
    assertThat(rule(() -> parse("{} []"))).isEqualTo("VALIDATION_ERROR:body/json");
  }

  @Test
  void amountTokensOtherThanIntegersAreInvalidAmount() {
    for (String token :
        new String[] {"1.0", "1E3", "\"1\"", "-0", "18446744073709551616", "null"}) {
      BodySchema.Validated v = parse("{\"amount\":" + token + ",\"currency\":\"INR\"}");
      assertThatThrownBy(() -> v.amount("amount"))
          .as(token)
          .isInstanceOfSatisfying(
              LedgerException.class, e -> assertThat(e.code()).isEqualTo(ErrorCode.INVALID_AMOUNT));
    }
  }

  @Test
  void optionalBodyMayBeEmptyAndNullStaysNull() {
    BodySchema reversal = BodySchema.of().field("reason", Kind.OPTIONAL_STRING);
    assertThat(reversal.validate(new byte[0], true).optionalText("reason", 140)).isNull();
    assertThat(
            reversal
                .validate("{\"reason\":null}".getBytes(StandardCharsets.UTF_8), true)
                .optionalText("reason", 140))
        .isNull();
    assertThat(
            reversal
                .validate("{\"reason\":\"\"}".getBytes(StandardCharsets.UTF_8), true)
                .optionalText("reason", 140))
        .isEmpty();
  }

  @Test
  void clientAddressTrustsForwardingOnlyFromProxies() {
    ClientAddress none = new ClientAddress(java.util.List.of());
    ClientAddress proxy = new ClientAddress(java.util.List.of("10.0.0.1", "10.0.0.2"));
    var xff = java.util.Collections.enumeration(java.util.List.of("1.1.1.1, 2.2.2.2, 10.0.0.2"));
    assertThat(none.resolve("10.0.0.1", xff)).isEqualTo("10.0.0.1");
    xff = java.util.Collections.enumeration(java.util.List.of("1.1.1.1, 2.2.2.2, 10.0.0.2"));
    assertThat(proxy.resolve("10.0.0.1", xff)).isEqualTo("2.2.2.2");
    assertThat(proxy.resolve("10.0.0.1", null)).isEqualTo("10.0.0.1");
    xff = java.util.Collections.enumeration(java.util.List.of("10.0.0.2"));
    assertThat(proxy.resolve("10.0.0.1", xff)).isEqualTo("10.0.0.1");
  }
}
