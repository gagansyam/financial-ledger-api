package org.ledgerapi.domain;

/** One ledger entry; entry numbers are global and gap-free (BR-11). */
public record Entry(
    long entryNumber,
    String transactionId,
    String accountId,
    Direction direction,
    long amount,
    String currency,
    String createdAt) {}
