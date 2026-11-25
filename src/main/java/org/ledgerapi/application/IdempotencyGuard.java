package org.ledgerapi.application;

import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.Optional;
import org.ledgerapi.domain.ErrorCode;
import org.ledgerapi.domain.IdempotencyRecord;
import org.ledgerapi.domain.LedgerException;
import org.ledgerapi.domain.LedgerTransaction;
import org.ledgerapi.domain.Timestamps;
import org.ledgerapi.provider.ClockProvider;
import org.ledgerapi.provider.HashProvider;
import org.ledgerapi.provider.LedgerUnit;

/** Idempotency lookup and recording inside the write transaction (ADR-0005, FR-2.5 to FR-2.8). */
public final class IdempotencyGuard {
  public static final int CREATED = 201;

  private final ClockProvider clock;
  private final HashProvider hashes;

  public IdempotencyGuard(ClockProvider clock, HashProvider hashes) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.hashes = Objects.requireNonNull(hashes, "hashes");
  }

  public String fingerprint(String canonicalInput) {
    return hashes.sha256Hex(canonicalInput.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Returns the stored response when the principal/key pair is recorded with the same fingerprint,
   * empty when the key is new, and rejects a reused key with a different fingerprint.
   */
  public Optional<MoneyResult> replay(
      LedgerUnit unit, String principal, String key, String fingerprint) {
    Optional<IdempotencyRecord> recorded = unit.findIdempotency(principal, key);
    if (recorded.isEmpty()) {
      return Optional.empty();
    }
    IdempotencyRecord record = recorded.get();
    if (!record.fingerprint().equals(fingerprint)) {
      throw new LedgerException(
          ErrorCode.IDEMPOTENCY_KEY_REUSED,
          "Idempotency key was already used with a different request");
    }
    return Optional.of(new MoneyResult(record.responseStatus(), record.responseBody(), true, null));
  }

  /** Records the response of a new transaction in the same write transaction. */
  public MoneyResult record(
      LedgerUnit unit, String principal, String key, String fingerprint, LedgerTransaction tx) {
    String body = TransactionRepresentation.json(tx, null);
    unit.insertIdempotency(
        new IdempotencyRecord(
            principal,
            key,
            fingerprint,
            CREATED,
            body,
            tx.transactionId(),
            Timestamps.format(clock.now())));
    return new MoneyResult(CREATED, body, false, tx);
  }
}
