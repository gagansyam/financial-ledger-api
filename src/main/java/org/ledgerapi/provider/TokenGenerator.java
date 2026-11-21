package org.ledgerapi.provider;

/**
 * Generates high-entropy opaque bearer tokens for authenticated user sessions (ADR-0008, FR-7.2).
 */
@FunctionalInterface
public interface TokenGenerator {
  /** Returns a base64url-encoded (unpadded) opaque token with at least 256 bits of entropy. */
  String generateToken();
}
