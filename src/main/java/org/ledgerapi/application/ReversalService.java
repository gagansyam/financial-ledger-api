package org.ledgerapi.application;

import java.util.Objects;
import java.util.Optional;
import org.ledgerapi.domain.Account;
import org.ledgerapi.domain.ErrorCode;
import org.ledgerapi.domain.LedgerException;
import org.ledgerapi.domain.LedgerTransaction;
import org.ledgerapi.domain.Principal;
import org.ledgerapi.domain.RequestFingerprint;
import org.ledgerapi.domain.TransactionType;
import org.ledgerapi.provider.LedgerStore;
import org.ledgerapi.provider.LedgerUnit;

/** {@code POST /admin/transactions/{transactionId}/reversal} (FR-4.2 to FR-4.4, BR-9). */
public final class ReversalService {
  private final LedgerStore store;
  private final IdempotencyGuard guard;
  private final TransactionPoster poster;

  public ReversalService(LedgerStore store, IdempotencyGuard guard, TransactionPoster poster) {
    this.store = Objects.requireNonNull(store, "store");
    this.guard = Objects.requireNonNull(guard, "guard");
    this.poster = Objects.requireNonNull(poster, "poster");
  }

  public MoneyResult reverse(
      Principal principal, String idempotencyKey, String transactionId, String reason) {
    if (!principal.isAdmin()) {
      throw Problems.forbidden();
    }
    String fingerprint = guard.fingerprint(RequestFingerprint.reversal(transactionId, reason));
    String namespace = principal.idempotencyPrincipal();
    return store.write(
        unit -> {
          Optional<MoneyResult> replay = guard.replay(unit, namespace, idempotencyKey, fingerprint);
          if (replay.isPresent()) {
            return replay.get();
          }
          LedgerTransaction tx = post(unit, transactionId, reason);
          return guard.record(unit, namespace, idempotencyKey, fingerprint, tx);
        });
  }

  private LedgerTransaction post(LedgerUnit unit, String transactionId, String reason) {
    LedgerTransaction original =
        unit.findTransaction(transactionId).orElseThrow(() -> Problems.notFound("Transaction"));
    if (original.type() == TransactionType.REVERSAL) {
      throw new LedgerException(
          ErrorCode.REVERSAL_NOT_ALLOWED, "A reversal cannot itself be reversed");
    }
    if (unit.findReversalOf(transactionId).isPresent()) {
      throw new LedgerException(
          ErrorCode.ALREADY_REVERSED, "The transaction has already been reversed");
    }
    Account debited =
        unit.findAccount(original.destinationAccountId())
            .orElseThrow(() -> Problems.missingAccount(original.destinationAccountId()));
    Account credited =
        unit.findAccount(original.sourceAccountId())
            .orElseThrow(() -> Problems.missingAccount(original.sourceAccountId()));
    return poster.post(
        unit,
        new TransactionPoster.Posting(
            TransactionType.REVERSAL,
            debited,
            credited,
            original.amount(),
            original.currency(),
            null,
            reason,
            original.transactionId()));
  }
}
