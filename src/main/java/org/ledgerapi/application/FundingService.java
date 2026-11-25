package org.ledgerapi.application;

import java.util.Objects;
import java.util.Optional;
import org.ledgerapi.domain.Account;
import org.ledgerapi.domain.LedgerTransaction;
import org.ledgerapi.domain.Principal;
import org.ledgerapi.domain.RequestFingerprint;
import org.ledgerapi.domain.TransactionType;
import org.ledgerapi.domain.Wallet;
import org.ledgerapi.provider.LedgerStore;
import org.ledgerapi.provider.LedgerUnit;

/** {@code POST /admin/wallets/{walletId}/funding} (FR-1.7). */
public final class FundingService {
  private final LedgerStore store;
  private final IdempotencyGuard guard;
  private final TransactionPoster poster;

  public FundingService(LedgerStore store, IdempotencyGuard guard, TransactionPoster poster) {
    this.store = Objects.requireNonNull(store, "store");
    this.guard = Objects.requireNonNull(guard, "guard");
    this.poster = Objects.requireNonNull(poster, "poster");
  }

  public MoneyResult fund(
      Principal principal, String idempotencyKey, String walletId, long amount, String currency) {
    if (!principal.isAdmin()) {
      throw Problems.forbidden();
    }
    String fingerprint = guard.fingerprint(RequestFingerprint.funding(walletId, amount, currency));
    String namespace = principal.idempotencyPrincipal();
    return store.write(
        unit -> {
          Optional<MoneyResult> replay = guard.replay(unit, namespace, idempotencyKey, fingerprint);
          if (replay.isPresent()) {
            return replay.get();
          }
          LedgerTransaction tx = post(unit, walletId, amount, currency);
          return guard.record(unit, namespace, idempotencyKey, fingerprint, tx);
        });
  }

  private LedgerTransaction post(LedgerUnit unit, String walletId, long amount, String currency) {
    Wallet wallet = unit.findWallet(walletId).orElseThrow(() -> Problems.notFound("Wallet"));
    if (!unit.isCurrencySupported(currency)) {
      throw Problems.unsupportedCurrency();
    }
    if (!wallet.currency().equals(currency)) {
      throw Problems.currencyMismatch();
    }
    Account system =
        unit.findSystemAccount(currency)
            .orElseThrow(() -> new IllegalStateException("No system account for " + currency));
    Account credited =
        unit.findAccount(walletId).orElseThrow(() -> Problems.missingAccount(walletId));
    return poster.post(
        unit,
        new TransactionPoster.Posting(
            TransactionType.FUNDING, system, credited, amount, currency, null, null, null));
  }
}
