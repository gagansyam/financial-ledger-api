package org.ledgerapi.provider;

import java.util.List;

/**
 * Raw ledger rows from one read snapshot (FR-5.2). Numeric columns holding something other than a
 * signed 64-bit integer are reported as {@code null}, so reconciliation can describe corrupt rows
 * instead of failing.
 */
public record LedgerSnapshot(
    List<String> currencies,
    List<AccountRow> accounts,
    List<TransactionRow> transactions,
    List<EntryRow> entries) {

  public LedgerSnapshot {
    currencies = List.copyOf(currencies);
    accounts = List.copyOf(accounts);
    transactions = List.copyOf(transactions);
    entries = List.copyOf(entries);
  }

  public record AccountRow(String accountId, String kind, String currency, Long balance) {}

  public record TransactionRow(
      String transactionId,
      Long sequence,
      String type,
      String sourceAccountId,
      String destinationAccountId,
      Long amount,
      String currency,
      String reference,
      String reason,
      String reversalOf,
      String createdAt,
      String previousHash,
      String hash) {}

  public record EntryRow(
      Long entryNumber,
      String transactionId,
      String accountId,
      String direction,
      Long amount,
      String currency,
      String createdAt) {}
}
