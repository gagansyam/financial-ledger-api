package org.ledgerapi.domain;

import java.util.List;

/**
 * A committed ledger transaction with its two entries in ascending entry-number order (DEBIT
 * first). {@code sourceAccountId} is debited and {@code destinationAccountId} credited.
 */
public record LedgerTransaction(
    String transactionId,
    long sequence,
    TransactionType type,
    String sourceAccountId,
    String destinationAccountId,
    long amount,
    String currency,
    String reference,
    String reason,
    String reversalOf,
    String createdAt,
    String previousHash,
    String hash,
    List<Entry> entries) {

  public LedgerTransaction {
    entries = List.copyOf(entries);
  }

  /** True when any entry posts to {@code accountId}. */
  public boolean touches(String accountId) {
    return entries.stream().anyMatch(e -> e.accountId().equals(accountId));
  }
}
