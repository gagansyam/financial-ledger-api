package org.ledgerapi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.io.TempDir;
import org.ledgerapi.application.MoneyResult;
import org.ledgerapi.application.ReconciliationService.Report;
import org.ledgerapi.application.TransferService.TransferRequest;
import org.ledgerapi.domain.Principal;
import org.ledgerapi.testing.ServiceFixture;

/**
 * A process killed in the middle of transfers leaves a consistent ledger, and retrying the
 * in-flight key either replays the committed result or posts it exactly once (NFR-REL-1, FR-3.8).
 */
class CrashRecoveryTest {
  @TempDir Path dir;

  private static long count(Path log, String prefix) throws IOException {
    if (!Files.exists(log)) {
      return 0;
    }
    return Files.readAllLines(log, StandardCharsets.UTF_8).stream()
        .filter(l -> l.startsWith(prefix))
        .count();
  }

  @RepeatedTest(3)
  void killedWriterRecoversAndRetryIsExactlyOnce() throws Exception {
    Path file = dir.resolve("ledger.db");
    String java = Path.of(System.getProperty("java.home"), "bin", "java").toString();
    Path log = dir.resolve("worker.log");
    Process child =
        new ProcessBuilder(
                java,
                "-cp",
                System.getProperty("java.class.path"),
                CrashWorker.class.getName(),
                file.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
    while (count(log, "done ") < 40 && child.isAlive() && System.nanoTime() < deadline) {
      Thread.sleep(5);
    }
    Thread.sleep(ThreadLocalRandom.current().nextInt(25));
    child.destroyForcibly();
    assertThat(child.waitFor(30, TimeUnit.SECONDS)).isTrue();
    String[] wallets = null;
    String lastBegun = null;
    long done = 0;
    for (String line : Files.readAllLines(log, StandardCharsets.UTF_8)) {
      if (line.startsWith("wallets ")) {
        wallets = line.substring(8).split(" ");
      } else if (line.startsWith("begin ")) {
        lastBegun = line.substring(6);
      } else if (line.startsWith("done ")) {
        done++;
      }
    }
    assertThat(wallets).as("worker output").isNotNull();
    assertThat(done).isGreaterThanOrEqualTo(40);
    assertThat(lastBegun).isNotNull();

    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement s = c.createStatement();
        ResultSet rs = s.executeQuery("PRAGMA integrity_check")) {
      assertThat(rs.next()).isTrue();
      assertThat(rs.getString(1)).isEqualTo("ok");
    }

    try (ServiceFixture f = ServiceFixture.randomIds(file)) {
      Report before = f.reconciliation.reconcile();
      assertThat(before.consistent()).as("%s", before.discrepancies()).isTrue();
      long transfersBefore = before.transactionCount() - 1;
      assertThat(transfersBefore).isBetween(done, done + 1);

      Principal alice = Principal.user(wallets[0], "00000000-0000-4000-8000-0000000000ff");
      MoneyResult retry =
          f.transfers.transfer(
              alice, lastBegun, new TransferRequest(wallets[1], wallets[2], 1, "INR", null));
      Report after = f.reconciliation.reconcile();
      assertThat(after.consistent()).isTrue();
      long transfersAfter = after.transactionCount() - 1;
      if (retry.replayed()) {
        assertThat(transfersAfter).isEqualTo(transfersBefore);
      } else {
        assertThat(transfersAfter).isEqualTo(transfersBefore + 1);
      }
      assertThat(f.balance(wallets[2])).isEqualTo(transfersAfter);
      assertThat(f.balance(wallets[1])).isEqualTo(1_000_000 - transfersAfter);
    }
  }
}
