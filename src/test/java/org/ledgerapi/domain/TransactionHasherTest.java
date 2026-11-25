package org.ledgerapi.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.ledgerapi.infrastructure.system.Sha256HashProvider;

/** Hash-chain input and known-answer hash (ADR-0004, FR-4.5). */
class TransactionHasherTest {
  private static final String TX = "00000000-0000-4000-8000-000000000003";
  private static final String A = "00000000-0000-4000-8000-000000000001";
  private static final String B = "00000000-0000-4000-8000-000000000002";
  private static final String AT = "2026-03-01T10:00:00.000Z";

  private static LedgerTransaction sample(long amount, List<Entry> entries) {
    return new LedgerTransaction(
        TX,
        1,
        TransactionType.TRANSFER,
        A,
        B,
        amount,
        "INR",
        "rent \"may\"",
        null,
        null,
        AT,
        TransactionHasher.GENESIS_HASH,
        null,
        entries);
  }

  private static List<Entry> entries(long amount) {
    return List.of(
        new Entry(2, TX, B, Direction.CREDIT, amount, "INR", AT),
        new Entry(1, TX, A, Direction.DEBIT, amount, "INR", AT));
  }

  @Test
  void knownAnswerVector() {
    String canonical = TransactionHasher.canonicalInput(sample(250, entries(250)));
    assertThat(canonical)
        .startsWith("{\"amount\":250,\"createdAt\":\"" + AT + "\"")
        .contains("\"entries\":[{\"accountId\":\"" + A + "\"")
        .contains("\"reference\":\"rent \\\"may\\\"\"")
        .contains("\"reason\":null")
        .doesNotContain("\"hash\"");
    String hash = new Sha256HashProvider().sha256Hex(canonical.getBytes(StandardCharsets.UTF_8));
    assertThat(hash).isEqualTo("9e9b041db8c8bd2e54931286abe23104eef61236aa5cc94173506e33acb1a103");
  }

  @Test
  void entryOrderInTheListDoesNotMatter() {
    List<Entry> reversed = List.of(entries(250).get(1), entries(250).get(0));
    assertThat(TransactionHasher.canonicalInput(sample(250, reversed)))
        .isEqualTo(TransactionHasher.canonicalInput(sample(250, entries(250))));
  }

  @Test
  void anyFieldChangeChangesTheInput() {
    assertThat(TransactionHasher.canonicalInput(sample(251, entries(251))))
        .isNotEqualTo(TransactionHasher.canonicalInput(sample(250, entries(250))));
  }

  @Test
  void genesisIsSixtyFourZeros() {
    assertThat(TransactionHasher.GENESIS_HASH).hasSize(64).matches("0+");
  }

  @Test
  void ordersEntriesByEntryNumberAndProducesStableChainHash() {
    TransactionHasher.HashEntry credit =
        new TransactionHasher.HashEntry(
            2L,
            "22222222-2222-4222-8222-222222222222",
            "CREDIT",
            2500L,
            "INR",
            "2026-11-21T10:00:00.000Z");
    TransactionHasher.HashEntry debit =
        new TransactionHasher.HashEntry(
            1L,
            "11111111-1111-4111-8111-111111111111",
            "DEBIT",
            2500L,
            "INR",
            "2026-11-21T10:00:00.000Z");

    TransactionHasher.HashInput txCreditFirst =
        new TransactionHasher.HashInput(
            "99999999-9999-4999-8999-999999999999",
            1L,
            "TRANSFER",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            2500L,
            "INR",
            "rent",
            null,
            null,
            "2026-11-21T10:00:00.000Z",
            TransactionHasher.GENESIS_HASH,
            List.of(credit, debit));

    TransactionHasher.HashInput txDebitFirst =
        new TransactionHasher.HashInput(
            "99999999-9999-4999-8999-999999999999",
            1L,
            "TRANSFER",
            "11111111-1111-4111-8111-111111111111",
            "22222222-2222-4222-8222-222222222222",
            2500L,
            "INR",
            "rent",
            null,
            null,
            "2026-11-21T10:00:00.000Z",
            TransactionHasher.GENESIS_HASH,
            List.of(debit, credit));

    assertThat(TransactionHasher.canonicalInput(txCreditFirst))
        .isEqualTo(TransactionHasher.canonicalInput(txDebitFirst));

    String hash1 = sha256(TransactionHasher.canonicalInput(txDebitFirst));
    assertThat(hash1).hasSize(64).matches("^[0-9a-f]{64}$");

    // Tampering with amount must change the hash
    TransactionHasher.HashInput tampered =
        new TransactionHasher.HashInput(
            txDebitFirst.transactionId(),
            txDebitFirst.sequence(),
            txDebitFirst.type(),
            txDebitFirst.sourceAccountId(),
            txDebitFirst.destinationAccountId(),
            2501L,
            txDebitFirst.currency(),
            txDebitFirst.reference(),
            txDebitFirst.reason(),
            txDebitFirst.reversalOf(),
            txDebitFirst.createdAt(),
            txDebitFirst.previousHash(),
            txDebitFirst.entries());

    assertThat(sha256(TransactionHasher.canonicalInput(tampered))).isNotEqualTo(hash1);
  }

  private static String sha256(String text) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException(e);
    }
  }
}
