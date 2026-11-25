package org.ledgerapi.domain;

/** A committed idempotent response (FR-2.8); retained for the life of the database. */
public record IdempotencyRecord(
    String principalId,
    String idempotencyKey,
    String fingerprint,
    int responseStatus,
    String responseBody,
    String transactionId,
    String createdAt) {}
