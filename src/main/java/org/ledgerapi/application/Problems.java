package org.ledgerapi.application;

import org.ledgerapi.domain.ErrorCode;
import org.ledgerapi.domain.LedgerException;

/** Shared rejections with stable, client-safe messages. */
final class Problems {
  private Problems() {}

  static LedgerException notFound(String what) {
    return new LedgerException(ErrorCode.NOT_FOUND, what + " not found");
  }

  static LedgerException forbidden() {
    return new LedgerException(ErrorCode.FORBIDDEN, "Not permitted for this principal");
  }

  static LedgerException unsupportedCurrency() {
    return new LedgerException(ErrorCode.UNSUPPORTED_CURRENCY, "Currency is not configured");
  }

  static LedgerException currencyMismatch() {
    return new LedgerException(
        ErrorCode.CURRENCY_MISMATCH, "Request currency does not match the account currency");
  }

  static IllegalStateException missingAccount(String accountId) {
    return new IllegalStateException("Ledger account missing for " + accountId);
  }
}
