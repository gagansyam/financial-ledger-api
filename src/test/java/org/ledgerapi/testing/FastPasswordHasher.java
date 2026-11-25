package org.ledgerapi.testing;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import org.ledgerapi.infrastructure.system.Sha256HashProvider;
import org.ledgerapi.provider.PasswordHasher;

/** A cheap stand-in for Argon2id so tests can register and log in quickly. Test use only. */
public final class FastPasswordHasher implements PasswordHasher {
  private static final String PREFIX = "test-only$";
  private final Sha256HashProvider sha = new Sha256HashProvider();

  @Override
  public String hash(String password) {
    return PREFIX + sha.sha256Hex(password.getBytes(StandardCharsets.UTF_8));
  }

  @Override
  public boolean verify(String password, String encoded) {
    return MessageDigest.isEqual(
        hash(password).getBytes(StandardCharsets.UTF_8), encoded.getBytes(StandardCharsets.UTF_8));
  }
}
