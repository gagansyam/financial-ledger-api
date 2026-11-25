package org.ledgerapi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.UnaryOperator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ledgerapi.application.MoneyResult;
import org.ledgerapi.domain.ErrorCode;
import org.ledgerapi.domain.LedgerException;
import org.ledgerapi.infrastructure.system.RandomUuidGenerator;
import org.ledgerapi.provider.StorageBusyException;
import org.ledgerapi.testing.ServiceFixture;
import org.ledgerapi.testing.ServiceFixture.Holder;

/** Concurrent money movement on a file-backed WAL database (FR-2.10, FR-3.2 to FR-3.6, TQ-3). */
class ConcurrencyTest {
  private static final int THREADS = 16;

  @TempDir Path dir;

  private static <T> List<Object> race(int tasks, Callable<T> work) throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(THREADS);
    CountDownLatch start = new CountDownLatch(1);
    List<Future<Object>> futures = new ArrayList<>();
    for (int i = 0; i < tasks; i++) {
      futures.add(
          pool.submit(
              () -> {
                start.await();
                try {
                  return work.call();
                } catch (LedgerException e) {
                  return e.code();
                }
              }));
    }
    start.countDown();
    List<Object> results = new ArrayList<>();
    for (Future<Object> f : futures) {
      results.add(f.get());
    }
    pool.shutdown();
    return results;
  }

  @Test
  void sharedSourceNeverOverdraws() throws Exception {
    try (ServiceFixture f = ServiceFixture.randomIds(dir.resolve("ledger.db"))) {
      Holder a = f.holder("alice");
      Holder b = f.holder("bob");
      f.fund(a.walletId(), 1_000);
      int[] counter = {0};
      List<Object> results =
          race(
              100,
              () -> {
                String key;
                synchronized (counter) {
                  key = String.format("race-key-%08d", counter[0]++);
                }
                return f.transfer(a, b.walletId(), 37, key);
              });
      long committed = results.stream().filter(MoneyResult.class::isInstance).count();
      assertThat(committed).isEqualTo(1_000 / 37);
      assertThat(results)
          .allMatch(r -> r instanceof MoneyResult || r == ErrorCode.INSUFFICIENT_FUNDS);
      assertThat(f.balance(a.walletId())).isEqualTo(1_000 - 37 * committed);
      assertThat(f.balance(b.walletId())).isEqualTo(37 * committed);
      assertThat(f.reconciliation.reconcile().consistent()).isTrue();
    }
  }

  @Test
  void sameKeyConcurrentlyPostsOnce() throws Exception {
    try (ServiceFixture f = ServiceFixture.randomIds(dir.resolve("ledger.db"))) {
      Holder a = f.holder("alice");
      Holder b = f.holder("bob");
      f.fund(a.walletId(), 1_000);
      List<Object> results = race(40, () -> f.transfer(a, b.walletId(), 10, "same-key-000000001"));
      Set<String> ids = new HashSet<>();
      long fresh = 0;
      for (Object r : results) {
        MoneyResult m = (MoneyResult) r;
        ids.add(m.body());
        fresh += m.replayed() ? 0 : 1;
      }
      assertThat(ids).hasSize(1);
      assertThat(fresh).isEqualTo(1);
      assertThat(f.balance(a.walletId())).isEqualTo(990);
      assertThat(f.reconciliation.reconcile().transactionCount()).isEqualTo(2);
    }
  }

  @Test
  void transfersInBothDirectionsKeepTheLedgerBalanced() throws Exception {
    try (ServiceFixture f = ServiceFixture.randomIds(dir.resolve("ledger.db"))) {
      Holder a = f.holder("alice");
      Holder b = f.holder("bob");
      f.fund(a.walletId(), 500);
      f.fund(b.walletId(), 500);
      int[] counter = {0};
      race(
          200,
          () -> {
            int n;
            synchronized (counter) {
              n = counter[0]++;
            }
            Holder from = n % 2 == 0 ? a : b;
            Holder to = n % 2 == 0 ? b : a;
            return f.transfer(from, to.walletId(), 1 + n % 7, String.format("both-key-%08d", n));
          });
      assertThat(f.balance(a.walletId()) + f.balance(b.walletId())).isEqualTo(1_000);
      var report = f.reconciliation.reconcile();
      assertThat(report.consistent()).isTrue();
      assertThat(report.transactionCount()).isEqualTo(202);
    }
  }

  @Test
  void heldWriteLockBecomesStorageBusyAndLeavesNoTrace() throws Exception {
    Path file = dir.resolve("ledger.db");
    try (ServiceFixture f =
        new ServiceFixture(
            file, UnaryOperator.identity(), new RandomUuidGenerator(), Duration.ofMillis(200))) {
      Holder a = f.holder("alice");
      Holder b = f.holder("bob");
      f.fund(a.walletId(), 100);
      try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
          Statement s = c.createStatement()) {
        s.execute("BEGIN IMMEDIATE");
        assertThatThrownBy(() -> f.transfer(a, b.walletId(), 10, "busy-key-00000001"))
            .isInstanceOf(StorageBusyException.class);
        s.execute("ROLLBACK");
      }
      MoneyResult retry = f.transfer(a, b.walletId(), 10, "busy-key-00000001");
      assertThat(retry.replayed()).isFalse();
      assertThat(retry.committed().sequence()).isEqualTo(2);
    }
  }
}
