package org.ledgerapi.infrastructure.system;

import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import org.bouncycastle.crypto.generators.Argon2BytesGenerator;
import org.bouncycastle.crypto.params.Argon2Parameters;
import org.ledgerapi.provider.PasswordHasher;

/**
 * BouncyCastle Argon2id password hasher conforming to ADR-0008 and NFR-SEC-4.
 *
 * <p>Uses a 16-byte random salt and 32-byte output encoded in PHC format: {@code
 * $argon2id$v=19$m=<kib>,t=<iterations>,p=<parallelism>$<b64salt>$<b64hash>}.
 */
public final class Argon2idPasswordHasher implements PasswordHasher {
  public static final int DEFAULT_MEMORY_KIB = 19_456;
  public static final int DEFAULT_ITERATIONS = 2;
  public static final int DEFAULT_PARALLELISM = 1;
  private static final int SALT_BYTES = 16;
  private static final int HASH_BYTES = 32;

  private final int memoryKib;
  private final int iterations;
  private final int parallelism;
  private final SecureRandom random;
  private final String dummyHash;

  public Argon2idPasswordHasher(int memoryKib, int iterations, int parallelism) {
    this(memoryKib, iterations, parallelism, new SecureRandom());
  }

  public Argon2idPasswordHasher(
      int memoryKib, int iterations, int parallelism, SecureRandom random) {
    if (memoryKib < 8) {
      throw new IllegalArgumentException("memoryKib must be >= 8");
    }
    if (iterations < 1) {
      throw new IllegalArgumentException("iterations must be >= 1");
    }
    if (parallelism < 1) {
      throw new IllegalArgumentException("parallelism must be >= 1");
    }
    this.memoryKib = memoryKib;
    this.iterations = iterations;
    this.parallelism = parallelism;
    this.random = Objects.requireNonNull(random, "random");
    this.dummyHash = hash("dummy-timing-equalization-secret".toCharArray());
  }

  public static Argon2idPasswordHasher withSrsMinimums() {
    return new Argon2idPasswordHasher(DEFAULT_MEMORY_KIB, DEFAULT_ITERATIONS, DEFAULT_PARALLELISM);
  }

  @Override
  public String hash(char[] password) {
    Objects.requireNonNull(password, "password");
    byte[] salt = new byte[SALT_BYTES];
    random.nextBytes(salt);
    byte[] pwdBytes = toUtf8Bytes(password);
    byte[] digest = new byte[HASH_BYTES];
    try {
      derive(pwdBytes, salt, memoryKib, iterations, parallelism, digest);
      Base64.Encoder encoder = Base64.getEncoder().withoutPadding();
      return "$argon2id$v=19$m="
          + memoryKib
          + ",t="
          + iterations
          + ",p="
          + parallelism
          + "$"
          + encoder.encodeToString(salt)
          + "$"
          + encoder.encodeToString(digest);
    } finally {
      Arrays.fill(pwdBytes, (byte) 0);
      Arrays.fill(digest, (byte) 0);
    }
  }

  @Override
  public boolean verify(char[] password, String encodedHash) {
    Objects.requireNonNull(password, "password");
    if (encodedHash == null || encodedHash.isBlank()) {
      return false;
    }
    String[] parts = encodedHash.split("\\$");
    if (parts.length != 6 || !"argon2id".equals(parts[1]) || !"v=19".equals(parts[2])) {
      return false;
    }
    int parsedMem = -1;
    int parsedIter = -1;
    int parsedPar = -1;
    for (String kv : parts[3].split(",")) {
      String[] pair = kv.split("=", 2);
      if (pair.length != 2) {
        return false;
      }
      try {
        int val = Integer.parseInt(pair[1]);
        switch (pair[0]) {
          case "m" -> parsedMem = val;
          case "t" -> parsedIter = val;
          case "p" -> parsedPar = val;
          default -> {
            return false;
          }
        }
      } catch (NumberFormatException e) {
        return false;
      }
    }
    if (parsedMem < 8 || parsedIter < 1 || parsedPar < 1) {
      return false;
    }
    byte[] salt;
    byte[] expected;
    try {
      salt = Base64.getDecoder().decode(parts[4]);
      expected = Base64.getDecoder().decode(parts[5]);
    } catch (IllegalArgumentException e) {
      return false;
    }
    if (salt.length == 0 || expected.length == 0) {
      return false;
    }
    byte[] pwdBytes = toUtf8Bytes(password);
    byte[] actual = new byte[expected.length];
    try {
      derive(pwdBytes, salt, parsedMem, parsedIter, parsedPar, actual);
      return MessageDigest.isEqual(expected, actual);
    } finally {
      Arrays.fill(pwdBytes, (byte) 0);
      Arrays.fill(actual, (byte) 0);
    }
  }

  @Override
  public void verifyDummy(char[] password) {
    verify(password, dummyHash);
  }

  private static void derive(
      byte[] pwdBytes, byte[] salt, int memoryKib, int iterations, int parallelism, byte[] output) {
    Argon2Parameters params =
        new Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
            .withVersion(Argon2Parameters.ARGON2_VERSION_13)
            .withSalt(salt)
            .withMemoryAsKB(memoryKib)
            .withIterations(iterations)
            .withParallelism(parallelism)
            .build();
    Argon2BytesGenerator generator = new Argon2BytesGenerator();
    generator.init(params);
    generator.generateBytes(pwdBytes, output);
  }

  private static byte[] toUtf8Bytes(char[] chars) {
    ByteBuffer buf = StandardCharsets.UTF_8.encode(CharBuffer.wrap(chars));
    byte[] bytes = new byte[buf.remaining()];
    buf.get(bytes);
    if (buf.hasArray()) {
      Arrays.fill(buf.array(), (byte) 0);
    }
    return bytes;
  }
}
