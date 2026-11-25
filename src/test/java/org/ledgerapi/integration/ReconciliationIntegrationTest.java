package org.ledgerapi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ledgerapi.application.ReconciliationService.CurrencyTotals;
import org.ledgerapi.application.ReconciliationService.Report;
import org.ledgerapi.application.TransactionPoster;
import org.ledgerapi.application.TransactionPoster.Posting;
import org.ledgerapi.domain.Account;
import org.ledgerapi.domain.LedgerException;
import org.ledgerapi.domain.LedgerRules;
import org.ledgerapi.domain.TransactionType;
import org.ledgerapi.infrastructure.system.Sha256HashProvider;
import org.ledgerapi.provider.LedgerUnit;
import org.ledgerapi.testing.ServiceFixture;
import org.ledgerapi.testing.ServiceFixture.Holder;

/**
 * Reconciliation on empty, concurrently written and very large ledgers (FR-5.1 to FR-5.4, BR-12,
 * TQ-6). Corrupted ledgers are covered by {@link ReconciliationCorruptionTest}.
 */
class ReconciliationIntegrationTest {
  private static final long MAX_AMOUNT = 1_000_000_000_000L;

  @TempDir Path dir;

  @Test
  void emptyLedgerIsConsistent() {
    try (ServiceFixture f = new ServiceFixture(dir.resolve("ledger.db"))) {
      Report report = f.reconciliation.reconcile();
      assertThat(report.consistent()).isTrue();
      assertThat(report.transactionCount()).isZero();
      assertThat(report.entryCount()).isZero();
      assertThat(report.snapshotSequence()).isZero();
      assertThat(report.totals())
          .containsExactly(new CurrencyTotals("INR", BigInteger.ZERO, BigInteger.ZERO));
      assertThat(report.toBody()).containsEntry("status", "CONSISTENT");
    }
  }

  @Test
  void reconciliationWhileTransfersCommitSeesConsistentSnapshots() throws Exception {
    try (ServiceFixture f = ServiceFixture.randomIds(dir.resolve("ledger.db"))) {
      Holder a = f.holder("alice");
      Holder b = f.holder("bob");
      f.fund(a.walletId(), 100_000);
      f.fund(b.walletId(), 100_000);

      AtomicBoolean writing = new AtomicBoolean(true);
      ExecutorService pool = Executors.newFixedThreadPool(3);
      List<Future<?>> writers = new ArrayList<>();
      for (int w = 0; w < 2; w++) {
        Holder from = w == 0 ? a : b;
        Holder to = w == 0 ? b : a;
        int writer = w;
        writers.add(
            pool.submit(
                () -> {
                  for (int i = 0; i < 60; i++) {
                    try {
                      f.transfer(
                          from,
                          to.walletId(),
                          7 + i,
                          String.format("recon-writer-%d-%08d", writer, i));
                    } catch (LedgerException e) {
                      // a lock timeout is acceptable here; it leaves no trace
                    }
                  }
                  return null;
                }));
      }
      Future<List<Report>> reader =
          pool.submit(
              () -> {
                List<Report> reports = new ArrayList<>();
                while (writing.get() || reports.size() < 3) {
                  reports.add(f.reconciliation.reconcile());
                }
                return reports;
              });
      for (Future<?> writer : writers) {
        writer.get(60, TimeUnit.SECONDS);
      }
      writing.set(false);
      List<Report> reports = reader.get(60, TimeUnit.SECONDS);
      pool.shutdown();

      assertThat(reports).allMatch(Report::consistent);
      assertThat(reports).allMatch(r -> r.entryCount() == 2 * r.transactionCount());
      assertThat(reports).allMatch(r -> r.snapshotSequence() == r.transactionCount());
      Report last = f.reconciliation.reconcile();
      assertThat(last.consistent()).isTrue();
      assertThat(f.balance(a.walletId()) + f.balance(b.walletId())).isEqualTo(200_000);
    }
  }

  @Test
  void aggregateTotalsBeyondTwoToTheFiftyThreeAreExact() {
    try (ServiceFixture f = new ServiceFixture(dir.resolve("ledger.db"))) {
      Holder a = f.holder("alice");
      Holder b = f.holder("bob");
      TransactionPoster poster = new TransactionPoster(f.clock, f.ids, new Sha256HashProvider());
      BigInteger limit = BigInteger.valueOf(LedgerRules.MAX_WALLET_BALANCE);
      int pairs = limit.divide(BigInteger.valueOf(2 * MAX_AMOUNT)).intValueExact() + 1;

      f.store.write(
          unit -> {
            post(unit, poster, TransactionType.FUNDING, system(unit), a.walletId());
            for (int i = 0; i < pairs; i++) {
              post(
                  unit,
                  poster,
                  TransactionType.TRANSFER,
                  account(unit, a.walletId()),
                  b.walletId());
              post(
                  unit,
                  poster,
                  TransactionType.TRANSFER,
                  account(unit, b.walletId()),
                  a.walletId());
            }
            return null;
          });

      long transactions = 1 + 2L * pairs;
      BigInteger expected =
          BigInteger.valueOf(MAX_AMOUNT).multiply(BigInteger.valueOf(transactions));
      assertThat(expected).isGreaterThan(limit);

      Report report = f.reconciliation.reconcile();
      assertThat(report.consistent()).isTrue();
      assertThat(report.transactionCount()).isEqualTo(transactions);
      assertThat(report.totals()).containsExactly(new CurrencyTotals("INR", expected, expected));
      @SuppressWarnings("unchecked")
      Map<String, Object> totals =
          ((List<Map<String, Object>>) report.toBody().get("totals")).get(0);
      assertThat(totals)
          .containsEntry("totalCredits", expected.toString())
          .containsEntry("totalDebits", expected.toString());
      assertThat(f.balance(a.walletId())).isEqualTo(MAX_AMOUNT);
      assertThat(f.balance(b.walletId())).isZero();
    }
  }

  private static Account system(LedgerUnit unit) {
    return unit.findSystemAccount("INR").orElseThrow();
  }

  private static Account account(LedgerUnit unit, String id) {
    return unit.findAccount(id).orElseThrow();
  }

  private static void post(
      LedgerUnit unit,
      TransactionPoster poster,
      TransactionType type,
      Account debited,
      String creditedId) {
    poster.post(
        unit,
        new Posting(type, debited, account(unit, creditedId), MAX_AMOUNT, "INR", null, null, null));
  }
}
