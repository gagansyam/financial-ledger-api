package org.ledgerapi.application;

import java.util.Objects;

/** The application services the HTTP adapter depends on. */
public record LedgerServices(
    HealthService health,
    UserService users,
    AuthService auth,
    AuthThrottle throttle,
    WalletService wallets,
    TransferService transfers,
    FundingService funding,
    ReversalService reversals,
    TransactionService transactions,
    ReconciliationService reconciliation) {

  public LedgerServices {
    Objects.requireNonNull(health, "health");
    Objects.requireNonNull(users, "users");
    Objects.requireNonNull(auth, "auth");
    Objects.requireNonNull(throttle, "throttle");
    Objects.requireNonNull(wallets, "wallets");
    Objects.requireNonNull(transfers, "transfers");
    Objects.requireNonNull(funding, "funding");
    Objects.requireNonNull(reversals, "reversals");
    Objects.requireNonNull(transactions, "transactions");
    Objects.requireNonNull(reconciliation, "reconciliation");
  }
}
