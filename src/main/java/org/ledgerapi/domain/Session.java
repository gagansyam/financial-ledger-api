package org.ledgerapi.domain;

import java.time.Instant;

/** A user session; only the SHA-256 hash of its token is stored (FR-7.2). */
public record Session(
    String sessionId,
    String userId,
    String tokenHash,
    Instant createdAt,
    Instant expiresAt,
    Instant revokedAt) {

  /** Active when not revoked and {@code now} is before the absolute expiry. */
  public boolean isActiveAt(Instant now) {
    return revokedAt == null && now.isBefore(expiresAt);
  }
}
