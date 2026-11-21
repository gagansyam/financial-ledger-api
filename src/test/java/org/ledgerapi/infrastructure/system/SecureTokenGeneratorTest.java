package org.ledgerapi.infrastructure.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;

class SecureTokenGeneratorTest {
  @Test
  void generatesUnpaddedBase64UrlTokensWith256BitsOfEntropy() {
    SecureTokenGenerator generator = new SecureTokenGenerator();
    Set<String> tokens = new HashSet<>();

    for (int i = 0; i < 64; i++) {
      String token = generator.generateToken();
      assertThat(token).doesNotContain("=").matches("^[A-Za-z0-9_-]{43}$");
      byte[] decoded = Base64.getUrlDecoder().decode(token);
      assertThat(decoded).hasSize(SecureTokenGenerator.TOKEN_BYTES);
      tokens.add(token);
    }

    assertThat(tokens).hasSize(64);
  }
}
