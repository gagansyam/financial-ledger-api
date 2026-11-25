package org.ledgerapi.infrastructure.system;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.ledgerapi.config.AppConfig.PasswordHashSettings;

/** Argon2id PHC strings with per-hash salts (NFR-SEC-4). */
class Argon2idPasswordHasherTest {
  private final Argon2idPasswordHasher hasher =
      new Argon2idPasswordHasher(new PasswordHashSettings(19 * 1024, 2, 1));

  @Test
  void hashesVerifyAndUseUniqueSalts() {
    String a = hasher.hash("correct horse battery");
    String b = hasher.hash("correct horse battery");
    assertThat(a).startsWith("$argon2id$v=19$m=19456,t=2,p=1$").isNotEqualTo(b);
    assertThat(hasher.verify("correct horse battery", a)).isTrue();
    assertThat(hasher.verify("correct horse batterY", a)).isFalse();
  }

  @Test
  void malformedStoredHashesNeverVerify() {
    for (String bad :
        new String[] {
          "",
          "plain",
          "$argon2i$v=19$m=19456,t=2,p=1$AAAA$AAAA",
          "$argon2id$v=19$m=x,t=2,p=1$AAAA$AAAA",
          "$argon2id$v=19$m=19456,t=2,p=1$!!!$AAAA"
        }) {
      assertThat(hasher.verify("anything", bad)).as(bad).isFalse();
    }
  }

  @Test
  void tokensAreUrlSafeAndDistinct() {
    SecureRandomTokenGenerator tokens = new SecureRandomTokenGenerator();
    String t = tokens.newToken();
    assertThat(t).matches("[A-Za-z0-9_-]{43}").isNotEqualTo(tokens.newToken());
  }
}
