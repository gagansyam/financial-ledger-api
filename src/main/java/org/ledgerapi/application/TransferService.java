package org.ledgerapi.application;

import java.util.Objects;
import java.util.Optional;
import org.ledgerapi.domain.Account;
import org.ledgerapi.domain.ErrorCode;
import org.ledgerapi.domain.LedgerException;
import org.ledgerapi.domain.Principal;
import org.ledgerapi.domain.RequestFingerprint;
import org.ledgerapi.domain.Role;
import org.ledgerapi.domain.TransactionType;
import org.ledgerapi.domain.Wallet;
import org.ledgerapi.provider.LedgerStore;
import org.ledgerapi.provider.LedgerUnit;

/** {@code POST /transfers} (FR-2, FR-3). */
public final class TransferService {
  private final LedgerStore store;
  private final IdempotencyGuard guard;
  private final TransactionPoster poster;

  public TransferService(LedgerStore store, IdempotencyGuard guard, TransactionPoster poster) {
    this.store = Objects.requireNonNull(store, "store");
    this.guard = Objects.requireNonNull(guard, "guard");
    this.poster = Objects.requireNonNull(poster, "poster");
  }

  /** Validated transfer request; {@code reference} may be null. */
  public record TransferRequest(
      String sourceWalletId,
      String destinationWalletId,
      long amount,
      String currency,
      String reference) {}

  public MoneyResult transfer(Principal principal, String idempotencyKey, TransferRequest request) {
    if (principal.role() != Role.USER) {
      throw Problems.forbidden();
    }
    String fingerprint =
        guard.fingerprint(
            RequestFingerprint.transfer(
                request.sourceWalletId(),
                request.destinationWalletId(),
                request.amount(),
                request.currency(),
                request.reference()));
    return store.write(unit -> execute(unit, principal, idempotencyKey, fingerprint, request));
  }

  private MoneyResult execute(
      LedgerUnit unit,
      Principal principal,
      String key,
      String fingerprint,
      TransferRequest request) {
    Wallet source =
        unit.findWallet(request.sourceWalletId())
            .orElseThrow(() -> Problems.notFound("Source wallet"));
    if (!source.userId().equals(principal.id())) {
      throw Problems.forbidden();
    }
    Optional<MoneyResult> replay =
        guard.replay(unit, principal.idempotencyPrincipal(), key, fingerprint);
    if (replay.isPresent()) {
      return replay.get();
    }
    Wallet destination =
        unit.findWallet(request.destinationWalletId())
            .orElseThrow(() -> Problems.notFound("Destination wallet"));
    if (source.walletId().equals(destination.walletId())) {
      throw new LedgerException(
          ErrorCode.SAME_WALLET_TRANSFER, "Source and destination wallets must differ");
    }
    if (!unit.isCurrencySupported(request.currency())) {
      throw Problems.unsupportedCurrency();
    }
    if (!source.currency().equals(request.currency())
        || !destination.currency().equals(request.currency())) {
      throw Problems.currencyMismatch();
    }
    Account debited =
        unit.findAccount(source.walletId())
            .orElseThrow(() -> Problems.missingAccount(source.walletId()));
    Account credited =
        unit.findAccount(destination.walletId())
            .orElseThrow(() -> Problems.missingAccount(destination.walletId()));
    var tx =
        poster.post(
            unit,
            new TransactionPoster.Posting(
                TransactionType.TRANSFER,
                debited,
                credited,
                request.amount(),
                request.currency(),
                request.reference(),
                null,
                null));
    return guard.record(unit, principal.idempotencyPrincipal(), key, fingerprint, tx);
  }
}
