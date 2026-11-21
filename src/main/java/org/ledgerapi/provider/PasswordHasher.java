package org.ledgerapi.provider;

/**
 * Password hashing port for user authentication (ADR-0008, FR-7.1, NFR-SEC-4).
 *
 * <p>Implementations must use Argon2id with constant-time verification and support dummy
 * verification to equalize timing for unknown login names.
 */
public interface PasswordHasher {
  /** Hashes a raw password using Argon2id and returns the PHC-formatted string. */
  String hash(char[] password);

  /** Verifies a raw password against a stored PHC-formatted Argon2id hash in constant time. */
  boolean verify(char[] password, String encodedHash);

  /** Runs a full Argon2id verification against a dummy hash to equalize unknown-login timing. */
  void verifyDummy(char[] password);
}
