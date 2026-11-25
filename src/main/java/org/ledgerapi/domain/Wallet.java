package org.ledgerapi.domain;

/** A user-owned wallet; its identifier is also its account identifier (BR-3). */
public record Wallet(
    String walletId, String userId, String currency, long balance, String createdAt) {}
