package org.ledgerapi.domain;

/** A ledger account with its cached balance (credits minus debits). */
public record Account(String accountId, AccountKind kind, String currency, long balance) {
  public boolean isWallet() {
    return kind == AccountKind.WALLET;
  }
}
