package org.ledgerapi.infrastructure.system;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import org.ledgerapi.provider.TokenGenerator;

/**
 * Generates 256-bit (32-byte) opaque session tokens encoded as unpadded base64url (ADR-0008,
 * FR-7.2).
 */
public final class SecureTokenGenerator implements TokenGenerator {
  public static final int TOKEN_BYTES = 32;

  private final SecureRandom random;

  public SecureTokenGenerator() {
    this(new SecureRandom());
  }

  public SecureTokenGenerator(SecureRandom random) {
    this.random = Objects.requireNonNull(random, "random");
  }

  @Override
  public String generateToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
