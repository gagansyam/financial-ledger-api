package org.ledgerapi.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;
import org.ledgerapi.domain.ErrorCode;
import org.ledgerapi.domain.LedgerException;
import org.ledgerapi.domain.Principal;
import org.ledgerapi.domain.Session;
import org.ledgerapi.domain.User;
import org.ledgerapi.provider.ClockProvider;
import org.ledgerapi.provider.HashProvider;
import org.ledgerapi.provider.IdGenerator;
import org.ledgerapi.provider.LedgerStore;
import org.ledgerapi.provider.PasswordHasher;
import org.ledgerapi.provider.TokenGenerator;

/** Login, session authentication, logout and administrator keys (FR-7, ADR-0008). */
public final class AuthService {
  private static final Pattern TOKEN = Pattern.compile("[A-Za-z0-9_-]{1,128}");
  private static final int MAX_ADMIN_KEY_LENGTH = 512;

  private final LedgerStore store;
  private final PasswordHasher passwords;
  private final TokenGenerator tokens;
  private final HashProvider hashes;
  private final ClockProvider clock;
  private final IdGenerator ids;
  private final AuthThrottle throttle;
  private final Duration sessionLifetime;
  private final List<AdminCredential> adminKeys;
  private final String dummyHash;

  /** A configured administrator principal and the SHA-256 hash of its key. */
  public record AdminCredential(String principalId, String keySha256Hex) {}

  /** A new session; the raw token is returned once and never stored. */
  public record LoginResult(String accessToken, Instant expiresAt, String userId) {}

  public AuthService(
      LedgerStore store,
      PasswordHasher passwords,
      TokenGenerator tokens,
      HashProvider hashes,
      ClockProvider clock,
      IdGenerator ids,
      AuthThrottle throttle,
      Duration sessionLifetime,
      List<AdminCredential> adminKeys) {
    this.store = Objects.requireNonNull(store, "store");
    this.passwords = Objects.requireNonNull(passwords, "passwords");
    this.tokens = Objects.requireNonNull(tokens, "tokens");
    this.hashes = Objects.requireNonNull(hashes, "hashes");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
    this.throttle = Objects.requireNonNull(throttle, "throttle");
    this.sessionLifetime = Objects.requireNonNull(sessionLifetime, "sessionLifetime");
    this.adminKeys = List.copyOf(adminKeys);
    this.dummyHash = passwords.hash(tokens.newToken());
  }

  public LoginResult login(String loginName, String password, String clientIp) {
    throttle.checkLogin(loginName, clientIp);
    Optional<User> user = store.read(unit -> unit.findUserByLogin(loginName));
    String encoded = user.map(User::passwordHash).orElse(dummyHash);
    boolean valid = passwords.verify(password, encoded) && user.isPresent();
    if (!valid) {
      throttle.recordLoginFailure(loginName, clientIp);
      throw invalidCredentials();
    }
    String userId = user.get().userId();
    String token = tokens.newToken();
    Instant now = clock.now();
    Instant expiresAt = now.plus(sessionLifetime);
    Session session = new Session(ids.newId(), userId, sha256(token), now, expiresAt, null);
    store.write(
        unit -> {
          unit.insertSession(session);
          return null;
        });
    return new LoginResult(token, session.expiresAt(), userId);
  }

  /** Resolves a bearer token to its USER principal, or rejects it as unauthenticated. */
  public Principal authenticateSession(String token) {
    if (token == null || !TOKEN.matcher(token).matches()) {
      throw unauthenticated();
    }
    String tokenHash = sha256(token);
    Session session =
        store
            .read(unit -> unit.findSessionByTokenHash(tokenHash))
            .orElseThrow(this::unauthenticated);
    if (!session.isActiveAt(clock.now())) {
      throw unauthenticated();
    }
    return Principal.user(session.userId(), session.sessionId());
  }

  /** Resolves an administrator key by constant-time comparison against every configured hash. */
  public Principal authenticateAdmin(String rawKey) {
    if (rawKey == null || rawKey.isEmpty() || rawKey.length() > MAX_ADMIN_KEY_LENGTH) {
      throw unauthenticated();
    }
    byte[] presented = sha256(rawKey).getBytes(StandardCharsets.US_ASCII);
    String matched = null;
    for (AdminCredential credential : adminKeys) {
      byte[] expected = credential.keySha256Hex().getBytes(StandardCharsets.US_ASCII);
      if (MessageDigest.isEqual(presented, expected) && matched == null) {
        matched = credential.principalId();
      }
    }
    if (matched == null) {
      throw unauthenticated();
    }
    return Principal.admin(matched);
  }

  public void logout(Principal principal) {
    requireUser(principal);
    store.write(
        unit -> {
          unit.revokeSession(principal.sessionId(), clock.now());
          return null;
        });
  }

  public void logoutAll(Principal principal) {
    requireUser(principal);
    store.write(unit -> unit.revokeAllSessions(principal.id(), clock.now()));
  }

  private static void requireUser(Principal principal) {
    if (principal.isAdmin()) {
      throw Problems.forbidden();
    }
  }

  private String sha256(String value) {
    return hashes.sha256Hex(value.getBytes(StandardCharsets.UTF_8));
  }

  private LedgerException unauthenticated() {
    return new LedgerException(ErrorCode.UNAUTHENTICATED, "Authentication required");
  }

  private LedgerException invalidCredentials() {
    return new LedgerException(ErrorCode.UNAUTHENTICATED, "Invalid login name or password");
  }
}
