package org.ledgerapi.application;

import org.ledgerapi.domain.ErrorCode;
import org.ledgerapi.domain.LedgerException;

/** {@code 429 TOO_MANY_REQUESTS} with the number of seconds for {@code Retry-After}. */
public final class TooManyRequestsException extends LedgerException {
  private final long retryAfterSeconds;

  public TooManyRequestsException(long retryAfterSeconds) {
    super(ErrorCode.TOO_MANY_REQUESTS, "Too many attempts; retry later");
    this.retryAfterSeconds = retryAfterSeconds;
  }

  public long retryAfterSeconds() {
    return retryAfterSeconds;
  }
}
