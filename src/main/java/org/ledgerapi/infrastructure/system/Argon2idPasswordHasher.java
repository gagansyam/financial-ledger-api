package org.ledgerapi.infrastructure.system;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.ledgerapi.config.AppConfig.PasswordHashSettings;
import org.ledgerapi.provider.PasswordHasher;

/**
 * Argon2id password hashing (NFR-SEC-4, ADR-0008). Hashes use a fresh 16-byte salt and a 32-byte
 * output and are encoded in the PHC string format, so stored hashes keep their own work settings
 * when the configuration is strengthened later.
 */
public final class Argon2idPasswordHasher implements PasswordHasher {
  private static final int SALT_BYTES = 16;
  private static final int HASH_BYTES = 32;
  private static final Pattern ENCODED =
      Pattern.compile(
          "\\$argon2id\\$v=19\\$m=(\\d{1,8}),t=(\\d{1,3}),p=(\\d{1,2})\\$([A-Za-z0-9+/]+)\\$([A-Za-z0-9+/]+)");
  private static final Base64.Encoder B64 = Base64.getEncoder().withoutPadding();
  private static final Base64.Decoder B64_DECODE = Base64.getDecoder();

  private final PasswordHashSettings settings;
  private final SecureRandom random;

  public Argon2idPasswordHasher(PasswordHashSettings settings) {
    this(settings, new SecureRandom());
  }

  Argon2idPasswordHasher(PasswordHashSettings settings, SecureRandom random) {
    this.settings = Objects.requireNonNull(settings, "settings");
    this.random = Objects.requireNonNull(random, "random");
  }

  @Override
  public String hash(String password) {
    byte[] salt = new byte[SALT_BYTES];
    random.nextBytes(salt);
    byte[] hash =
        derive(
            password,
            salt,
            settings.memoryKib(),
            settings.iterations(),
            settings.parallelism(),
            HASH_BYTES);
    return "$argon2id$v=19$m="
        + settings.memoryKib()
        + ",t="
        + settings.iterations()
        + ",p="
        + settings.parallelism()
        + "$"
        + B64.encodeToString(salt)
        + "$"
        + B64.encodeToString(hash);
  }

  @Override
  public boolean verify(String password, String encodedHash) {
    if (encodedHash == null) {
      return false;
    }
    Matcher m = ENCODED.matcher(encodedHash);
    if (!m.matches()) {
      return false;
    }
    byte[] salt;
    byte[] expected;
    try {
      salt = B64_DECODE.decode(m.group(4));
      expected = B64_DECODE.decode(m.group(5));
    } catch (IllegalArgumentException e) {
      return false;
    }
    int memory = Integer.parseInt(m.group(1));
    int iterations = Integer.parseInt(m.group(2));
    int parallelism = Integer.parseInt(m.group(3));
    if (expected.length == 0 || iterations < 1 || parallelism < 1 || memory < 8 * parallelism) {
      return false;
    }
    byte[] actual = derive(password, salt, memory, iterations, parallelism, expected.length);
    return MessageDigest.isEqual(expected, actual);
  }

  private static byte[] derive(
      String password, byte[] salt, int memoryKib, int iterations, int parallelism, int length) {
    Argon2Parameters parameters =
        new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build();
    Argon2BytesGenerator generator = new Argon2BytesGenerator();
    generator.init(parameters);
    byte[] out = new byte[length];
    generator.generateBytes(password.getBytes(StandardCharsets.UTF_8), out);
    return out;
  }
}
