package org.ledgerapi.application;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;
import org.ledgerapi.domain.Account;
import org.ledgerapi.domain.Direction;
import org.ledgerapi.domain.Entry;
import org.ledgerapi.domain.LedgerRules;
import org.ledgerapi.domain.LedgerTransaction;
import org.ledgerapi.domain.Timestamps;
import org.ledgerapi.domain.TransactionHasher;
import org.ledgerapi.domain.TransactionType;
import org.ledgerapi.provider.ClockProvider;
import org.ledgerapi.provider.HashProvider;
import org.ledgerapi.provider.IdGenerator;
import org.ledgerapi.provider.LedgerUnit;
import org.ledgerapi.provider.LedgerUnit.LedgerHead;

/**
 * Posts one balanced two-entry transaction inside the caller's write transaction (FR-3.1): checks
 * the balance rules, allocates the next sequence and entry numbers, extends the hash chain, inserts
 * the records and updates both cached balances.
 */
public final class TransactionPoster {
  private final ClockProvider clock;
  private final IdGenerator ids;
  private final HashProvider hashes;

  public TransactionPoster(ClockProvider clock, IdGenerator ids, HashProvider hashes) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.hashes = Objects.requireNonNull(hashes, "hashes");
  }

  /**
   * Request fields of a posting; {@code debited} is the source and {@code credited} the
   * destination.
   */
  public record Posting(
      TransactionType type,
      Account debited,
      Account credited,
      long amount,
      String currency,
      String reference,
      String reason,
      String reversalOf) {}

  public LedgerTransaction post(LedgerUnit unit, Posting posting) {
    LedgerRules.Postings balances =
        LedgerRules.apply(posting.debited(), posting.credited(), posting.amount());
    LedgerHead head = unit.head();
    String transactionId = ids.newId();
    String createdAt = Timestamps.format(clock.now());
    long sequence = Math.addExact(head.lastSequence(), 1);
    long debitNumber = Math.addExact(head.lastEntryNumber(), 1);
    long creditNumber = Math.addExact(head.lastEntryNumber(), 2);
    List<Entry> entries =
        List.of(
            new Entry(
                debitNumber,
                transactionId,
                posting.debited().accountId(),
                Direction.DEBIT,
                posting.amount(),
                posting.currency(),
                createdAt),
            new Entry(
                creditNumber,
                transactionId,
                posting.credited().accountId(),
                Direction.CREDIT,
                posting.amount(),
                posting.currency(),
                createdAt));
    LedgerTransaction unhashed =
        new LedgerTransaction(
            transactionId,
            sequence,
            posting.type(),
            posting.debited().accountId(),
            posting.credited().accountId(),
            posting.amount(),
            posting.currency(),
            posting.reference(),
            posting.reason(),
            posting.reversalOf(),
            createdAt,
            head.lastHash(),
            null,
            entries);
    String hash =
        hashes.sha256Hex(
            TransactionHasher.canonicalInput(unhashed).getBytes(StandardCharsets.UTF_8));
    LedgerTransaction tx =
        new LedgerTransaction(
            transactionId,
            sequence,
            posting.type(),
            unhashed.sourceAccountId(),
            unhashed.destinationAccountId(),
            posting.amount(),
            posting.currency(),
            posting.reference(),
            posting.reason(),
            posting.reversalOf(),
            createdAt,
            head.lastHash(),
            hash,
            entries);
    unit.insertTransaction(tx);
    unit.updateBalance(posting.debited().accountId(), balances.debitedBalance());
    unit.updateBalance(posting.credited().accountId(), balances.creditedBalance());
    return tx;
  }
}
