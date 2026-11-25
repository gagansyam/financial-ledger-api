package org.ledgerapi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {
  @Test
  void transferFingerprintPinsCanonicalFormAndDistinguishesNullFromEmpty() {
    String canonicalNull =
        RequestFingerprint.transfer(
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            5000L,
            "INR",
            null);
    assertThat(canonicalNull)
        .isEqualTo(
            "{\"body\":{\"amount\":5000,\"currency\":\"INR\","
                + "\"destinationWalletId\":\"22222222-2222-4222-8222-222222222222\","
                + "\"reference\":null,"
                + "\"sourceWalletId\":\"11111111-1111-4111-8111-111111111111\"},"
                + "\"op\":\"TRANSFER\",\"path\":{}}");

    String hashNull =
        sha256(
            RequestFingerprint.transfer(
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
                5000L,
                "INR",
                null));
    String hashEmpty =
        sha256(
            RequestFingerprint.transfer(
                "11111111-1111-4111-8111-111111111111",
                "22222222-2222-4222-8222-222222222222",
                5000L,
                "INR",
                ""));

    assertThat(hashNull).hasSize(64).matches("^[0-9a-f]{64}$");
    assertThat(hashNull).isNotEqualTo(hashEmpty);
  }

  @Test
  void fundingAndReversalFingerprintsAreDeterministic() {
    String fundingCanonical =
        RequestFingerprint.funding("11111111-1111-4111-8111-111111111111", 1000L, "USD");
    assertThat(fundingCanonical)
        .isEqualTo(
            "{\"body\":{\"amount\":1000,\"currency\":\"USD\"},"
                + "\"op\":\"FUNDING\","
                + "\"path\":{\"walletId\":\"11111111-1111-4111-8111-111111111111\"}}");
    assertThat(
            sha256(
                RequestFingerprint.funding("11111111-1111-4111-8111-111111111111", 1000L, "USD")))
        .hasSize(64);

    String reversalCanonical =
        RequestFingerprint.reversal("33333333-3333-4333-8333-333333333333", null);
    assertThat(reversalCanonical)
        .isEqualTo(
            "{\"body\":{\"reason\":null},"
                + "\"op\":\"REVERSAL\","
                + "\"path\":{\"transactionId\":\"33333333-3333-4333-8333-333333333333\"}}");
    assertThat(
            sha256(
                RequestFingerprint.reversal("33333333-3333-4333-8333-333333333333", "duplicate")))
        .isNotEqualTo(
            sha256(RequestFingerprint.reversal("33333333-3333-4333-8333-333333333333", null)));
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
