package org.ledgerapi.domain;

import java.util.Objects;

/**
 * The authenticated caller. For USER, {@code id} is the user identifier and {@code sessionId} the
 * session that authenticated the request; for ADMIN, {@code id} is the configured principal
 * identifier and {@code sessionId} is null.
 */
public record Principal(Role role, String id, String sessionId) {
  public Principal {
    Objects.requireNonNull(role, "role");
    Objects.requireNonNull(id, "id");
  }

  public static Principal user(String userId, String sessionId) {
    return new Principal(Role.USER, userId, Objects.requireNonNull(sessionId, "sessionId"));
  }

  public static Principal admin(String principalId) {
    return new Principal(Role.ADMIN, principalId, null);
  }

  public boolean isAdmin() {
    return role == Role.ADMIN;
  }

  /** Idempotency namespace; the prefixes keep user and admin principals distinct (SRS 4.6). */
  public String idempotencyPrincipal() {
    return (role == Role.ADMIN ? "admin:" : "user:") + id;
  }
}
