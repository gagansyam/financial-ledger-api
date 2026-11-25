package org.ledgerapi.application;

import java.util.Objects;
import org.ledgerapi.domain.LedgerTransaction;
import org.ledgerapi.domain.Principal;
import org.ledgerapi.provider.LedgerStore;

/** {@code GET /transactions/{transactionId}} (FR-2.11, NFR-SEC-7). */
public final class TransactionService {
  private final LedgerStore store;

  public TransactionService(LedgerStore store) {
    this.store = Objects.requireNonNull(store, "store");
  }

  /** Returns the transaction body with {@code reversedBy} derived from the same snapshot. */
  public String get(Principal principal, String transactionId) {
    return store.read(
        unit -> {
          LedgerTransaction tx =
              unit.findTransaction(transactionId)
                  .orElseThrow(() -> Problems.notFound("Transaction"));
          if (!principal.isAdmin() && !unit.isTransactionVisibleTo(transactionId, principal.id())) {
            throw Problems.forbidden();
          }
          String reversedBy = unit.findReversalOf(transactionId).orElse(null);
          return TransactionRepresentation.json(tx, reversedBy);
        });
  }
}
