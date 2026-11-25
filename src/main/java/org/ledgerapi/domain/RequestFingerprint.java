package org.ledgerapi.domain;

import java.util.HashMap;
import java.util.Map;

/**
 * Canonical fingerprint input {@code F} of each money-moving request (ADR-0004, FR-2.5). The caller
 * hashes the returned text with SHA-256; values come from the parsed, validated request so member
 * order and whitespace never affect the result.
 */
public final class RequestFingerprint {
  private RequestFingerprint() {}

  public static String transfer(
      String sourceWalletId,
      String destinationWalletId,
      long amount,
      String currency,
      String reference) {
    Map<String, Object> body = new HashMap<>();
    body.put("amount", amount);
    body.put("currency", currency);
    body.put("destinationWalletId", destinationWalletId);
    body.put("reference", reference);
    body.put("sourceWalletId", sourceWalletId);
    return of("TRANSFER", Map.of(), body);
  }

  public static String funding(String walletId, long amount, String currency) {
    Map<String, Object> body = new HashMap<>();
    body.put("amount", amount);
    body.put("currency", currency);
    return of("FUNDING", Map.of("walletId", walletId), body);
  }

  public static String reversal(String transactionId, String reason) {
    Map<String, Object> body = new HashMap<>();
    body.put("reason", reason);
    return of("REVERSAL", Map.of("transactionId", transactionId), body);
  }

  private static String of(String op, Map<String, Object> path, Map<String, Object> body) {
    Map<String, Object> input = new HashMap<>();
    input.put("op", op);
    input.put("path", path);
    input.put("body", body);
    return CanonicalJson.canonical(input);
  }
}
