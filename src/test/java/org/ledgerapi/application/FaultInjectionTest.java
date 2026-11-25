package org.ledgerapi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.ledgerapi.domain.IdempotencyRecord;
import org.ledgerapi.provider.LedgerStore;
import org.ledgerapi.provider.LedgerUnit;
import org.ledgerapi.provider.StorageException;
import org.ledgerapi.testing.SequentialIds;
import org.ledgerapi.testing.ServiceFixture;
import org.ledgerapi.testing.ServiceFixture.Holder;

/** A failure at any persistence boundary rolls back every write of the operation (FR-3.8, TQ-2). */
class FaultInjectionTest {
  @TempDir Path dir;

  /** Fails the {@code occurrence}-th call of {@code method} on units handed out while armed. */
  private static final class FaultyStore implements LedgerStore {
    private final LedgerStore delegate;
    private final String method;
    private final int occurrence;
    volatile boolean armed;

    FaultyStore(LedgerStore delegate, String method, int occurrence) {
      this.delegate = delegate;
      this.method = method;
      this.occurrence = occurrence;
    }

    @Override
    public <T> T write(Work<T> work) {
      return delegate.write(unit -> work.execute(wrap(unit)));
    }

    @Override
    public <T> T read(Work<T> work) {
      return delegate.read(work);
    }

    private LedgerUnit wrap(LedgerUnit unit) {
      if (!armed) {
        return unit;
      }
      AtomicInteger calls = new AtomicInteger();
      return (LedgerUnit)
          Proxy.newProxyInstance(
              LedgerUnit.class.getClassLoader(),
              new Class<?>[] {LedgerUnit.class},
              (proxy, m, args) -> {
                if (m.getName().equals(method) && calls.incrementAndGet() == occurrence) {
                  throw new StorageException("injected failure before " + method, null);
                }
                try {
                  return m.invoke(unit, args);
                } catch (InvocationTargetException e) {
                  throw e.getCause();
                }
              });
    }
  }

  @ParameterizedTest
  @CsvSource({"insertTransaction,1", "updateBalance,1", "updateBalance,2", "insertIdempotency,1"})
  void transferRollsBackCompletely(String method, int occurrence) {
    FaultyStore[] faulty = new FaultyStore[1];
    try (ServiceFixture f =
        new ServiceFixture(
            dir.resolve("ledger.db"),
            s -> faulty[0] = new FaultyStore(s, method, occurrence),
            new SequentialIds())) {
      Holder a = f.holder("alice");
      Holder b = f.holder("bob");
      f.fund(a.walletId(), 100);
      faulty[0].armed = true;
      assertThatThrownBy(() -> f.transfer(a, b.walletId(), 40, "transfer-key-0001"))
          .isInstanceOf(StorageException.class);
      faulty[0].armed = false;

      assertThat(f.balance(a.walletId())).isEqualTo(100);
      assertThat(f.balance(b.walletId())).isZero();
      long sequence = f.store.read(u -> u.head().lastSequence());
      assertThat(sequence).isEqualTo(1);
      Optional<IdempotencyRecord> record =
          f.store.read(u -> u.findIdempotency("user:" + a.principal().id(), "transfer-key-0001"));
      assertThat(record).isEmpty();

      MoneyResult retried = f.transfer(a, b.walletId(), 40, "transfer-key-0001");
      assertThat(retried.replayed()).isFalse();
      assertThat(retried.committed().sequence()).isEqualTo(2);
      assertThat(f.reconciliation.reconcile().consistent()).isTrue();
    }
  }

  @ParameterizedTest
  @CsvSource({"insertTransaction,1", "updateBalance,2", "insertIdempotency,1"})
  void fundingAndReversalRollBack(String method, int occurrence) {
    FaultyStore[] faulty = new FaultyStore[1];
    try (ServiceFixture f =
        new ServiceFixture(
            dir.resolve("ledger.db"),
            s -> faulty[0] = new FaultyStore(s, method, occurrence),
            new SequentialIds())) {
      Holder a = f.holder("alice");
      String original = f.fund(a.walletId(), 100).committed().transactionId();
      faulty[0].armed = true;
      assertThatThrownBy(() -> f.fund(a.walletId(), 5)).isInstanceOf(StorageException.class);
      assertThatThrownBy(
              () -> f.reversals.reverse(ServiceFixture.ADMIN, "reversal-key-0001", original, null))
          .isInstanceOf(StorageException.class);
      faulty[0].armed = false;
      assertThat(f.balance(a.walletId())).isEqualTo(100);
      Optional<String> reversal = f.store.read(u -> u.findReversalOf(original));
      assertThat(reversal).isEmpty();
      assertThat(f.reconciliation.reconcile().consistent()).isTrue();
    }
  }
}
