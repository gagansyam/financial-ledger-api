package org.ledgerapi.infrastructure.system;

import java.security.SecureRandom;
import java.util.Base64;
import org.ledgerapi.provider.TokenGenerator;

/** 256-bit session tokens from {@link SecureRandom}, base64url without padding (ADR-0008). */
public final class SecureRandomTokenGenerator implements TokenGenerator {
  private static final int TOKEN_BYTES = 32;
  private final SecureRandom random = new SecureRandom();

  @Override
  public String newToken() {
    byte[] bytes = new byte[TOKEN_BYTES];
    random.nextBytes(bytes);
    return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
  }
}
