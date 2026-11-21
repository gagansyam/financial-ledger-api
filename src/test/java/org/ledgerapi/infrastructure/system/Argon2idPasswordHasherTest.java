package org.ledgerapi.infrastructure.system;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class Argon2idPasswordHasherTest {
  @Test
  void hashesAndVerifiesPasswordsWithUniqueSalts() {
    // Low parameters for fast unit test; SRS minimums are tested separately below.
    Argon2idPasswordHasher hasher = new Argon2idPasswordHasher(1024, 1, 1);

    String hash1 = hasher.hash("correct-horse-battery-staple".toCharArray());
    String hash2 = hasher.hash("correct-horse-battery-staple".toCharArray());

    assertThat(hash1).startsWith("$argon2id$v=19$m=1024,t=1,p=1$");
    assertThat(hash1).isNotEqualTo(hash2);
    assertThat(hasher.verify("correct-horse-battery-staple".toCharArray(), hash1)).isTrue();
    assertThat(hasher.verify("correct-horse-battery-staple".toCharArray(), hash2)).isTrue();
    assertThat(hasher.verify("wrong-password".toCharArray(), hash1)).isFalse();
  }

  @Test
  void srsMinimumDefaultsProduceValidPhcHash() {
    Argon2idPasswordHasher hasher = Argon2idPasswordHasher.withSrsMinimums();
    String encoded = hasher.hash("SrsMinPassword!42".toCharArray());

    assertThat(encoded).startsWith("$argon2id$v=19$m=19456,t=2,p=1$");
    assertThat(hasher.verify("SrsMinPassword!42".toCharArray(), encoded)).isTrue();
    hasher.verifyDummy("nonexistent-user-attempt".toCharArray());
  }

  @Test
  void rejectsMalformedEncodedHashesGracefully() {
    Argon2idPasswordHasher hasher = new Argon2idPasswordHasher(1024, 1, 1);

    assertThat(hasher.verify("pw".toCharArray(), null)).isFalse();
    assertThat(hasher.verify("pw".toCharArray(), "")).isFalse();
    assertThat(hasher.verify("pw".toCharArray(), "$bcrypt$v=19$m=1024,t=1,p=1$aa$bb")).isFalse();
    assertThat(hasher.verify("pw".toCharArray(), "$argon2id$v=19$bad$aa$bb")).isFalse();
    assertThat(hasher.verify("pw".toCharArray(), "$argon2id$v=19$m=abc,t=1,p=1$aa$bb")).isFalse();
    assertThat(hasher.verify("pw".toCharArray(), "$argon2id$v=19$m=1024,t=1,p=1$!!!$bb")).isFalse();
  }

  @Test
  void rejectsInvalidParameters() {
    assertThatThrownBy(() -> new Argon2idPasswordHasher(4, 1, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Argon2idPasswordHasher(1024, 0, 1))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Argon2idPasswordHasher(1024, 1, 0))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
