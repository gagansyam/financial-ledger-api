package org.ledgerapi.provider;

/** Password hashing port (NFR-SEC-4). Encoded hashes carry algorithm, salt and work settings. */
public interface PasswordHasher {
  String hash(String password);

  /** Constant-time verification of {@code password} against an encoded hash. */
  boolean verify(String password, String encodedHash);
}
