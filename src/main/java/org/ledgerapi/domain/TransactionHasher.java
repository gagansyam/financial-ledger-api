package org.ledgerapi.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Canonical hash input {@code H} of a transaction (ADR-0004, FR-4.5). The stored {@code hash} is
 * excluded, as is the derived {@code reversedBy}; {@code sequence} and {@code entryNumber} are JSON
 * integers here although the public API writes them as strings.
 */
public final class TransactionHasher {
  /** {@code previousHash} of the first transaction. */
  public static final String GENESIS_HASH = "0".repeat(64);

  private TransactionHasher() {}

  /**
   * Hash-relevant fields as stored. Numeric fields are nullable so reconciliation can recompute the
   * input of a corrupt row; a null becomes JSON {@code null} and the hash then cannot match.
   */
  public record HashInput(
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
      List<HashEntry> entries) {}

  /** One entry of {@link HashInput}. */
  public record HashEntry(
      Long entryNumber,
      String accountId,
      String direction,
      Long amount,
      String currency,
      String createdAt) {}

  public static String canonicalInput(LedgerTransaction tx) {
    List<HashEntry> entries = new ArrayList<>();
    for (Entry e : tx.entries()) {
      entries.add(
          new HashEntry(
              e.entryNumber(),
              e.accountId(),
              e.direction().name(),
              e.amount(),
              e.currency(),
              e.createdAt()));
    }
    return canonicalInput(
        new HashInput(
            tx.transactionId(),
            tx.sequence(),
            tx.type().name(),
            tx.sourceAccountId(),
            tx.destinationAccountId(),
            tx.amount(),
            tx.currency(),
            tx.reference(),
            tx.reason(),
            tx.reversalOf(),
            tx.createdAt(),
            tx.previousHash(),
            entries));
  }

  public static String canonicalInput(HashInput tx) {
    List<HashEntry> ordered = new ArrayList<>(tx.entries());
    ordered.sort(
        Comparator.comparing(
            HashEntry::entryNumber, Comparator.nullsFirst(Comparator.naturalOrder())));
    List<Object> entries = new ArrayList<>();
    for (HashEntry entry : ordered) {
      Map<String, Object> item = new HashMap<>();
      item.put("accountId", entry.accountId());
      item.put("amount", entry.amount());
      item.put("createdAt", entry.createdAt());
      item.put("currency", entry.currency());
      item.put("direction", entry.direction());
      item.put("entryNumber", entry.entryNumber());
      entries.add(item);
    }
    Map<String, Object> input = new HashMap<>();
    input.put("amount", tx.amount());
    input.put("createdAt", tx.createdAt());
    input.put("currency", tx.currency());
    input.put("destinationAccountId", tx.destinationAccountId());
    input.put("entries", entries);
    input.put("previousHash", tx.previousHash());
    input.put("reason", tx.reason());
    input.put("reference", tx.reference());
    input.put("reversalOf", tx.reversalOf());
    input.put("sequence", tx.sequence());
    input.put("sourceAccountId", tx.sourceAccountId());
    input.put("transactionId", tx.transactionId());
    input.put("type", tx.type());
    return CanonicalJson.canonical(input);
  }
}
