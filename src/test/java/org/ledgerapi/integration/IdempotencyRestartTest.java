package org.ledgerapi.integration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ledgerapi.application.MoneyResult;
import org.ledgerapi.application.TransferService.TransferRequest;
import org.ledgerapi.domain.ErrorCode;
import org.ledgerapi.domain.LedgerException;
import org.ledgerapi.domain.Principal;
import org.ledgerapi.testing.ServiceFixture;
import org.ledgerapi.testing.ServiceFixture.Holder;

/**
 * Funding, transfer and reversal replays survive a restart and later business state changes, and a
 * changed payload under the same key is still rejected (FR-2.6 to FR-2.9, SRS 11.1 items 4 and 5).
 */
class IdempotencyRestartTest {
  private static final String FUND_KEY = "restart-fund-0001";
  private static final String TRANSFER_KEY = "restart-transfer-0001";
  private static final String REVERSAL_KEY = "restart-reversal-0001";

  @TempDir Path dir;

  @Test
  void replaysAfterRestartAndStateChangesCreateNothing() {
    Path file = dir.resolve("ledger.db");
    Holder alice;
    Holder bob;
    MoneyResult funding;
    MoneyResult transfer;
    MoneyResult reversal;
    try (ServiceFixture f = ServiceFixture.randomIds(file)) {
      alice = f.holder("alice");
      bob = f.holder("bob");
      funding = f.funding.fund(ServiceFixture.ADMIN, FUND_KEY, alice.walletId(), 1_000, "INR");
      transfer = f.transfer(alice, bob.walletId(), 600, TRANSFER_KEY);
      reversal =
          f.reversals.reverse(
              ServiceFixture.ADMIN,
              REVERSAL_KEY,
              transfer.committed().transactionId(),
              "duplicate");
      // Spend everything, so a fresh execution of the transfer would now be rejected.
      f.transfer(alice, bob.walletId(), 1_000, "restart-drain-0001");
      assertThat(f.balance(alice.walletId())).isZero();
    }

    try (ServiceFixture f = ServiceFixture.randomIds(file)) {
      long before = f.reconciliation.reconcile().transactionCount();
      Principal aliceAgain = Principal.user(alice.principal().id(), "new-session-after-restart");

      MoneyResult fundingReplay =
          f.funding.fund(ServiceFixture.ADMIN, FUND_KEY, alice.walletId(), 1_000, "INR");
      MoneyResult transferReplay =
          f.transfers.transfer(
              aliceAgain,
              TRANSFER_KEY,
              new TransferRequest(alice.walletId(), bob.walletId(), 600, "INR", null));
      MoneyResult reversalReplay =
          f.reversals.reverse(
              ServiceFixture.ADMIN,
              REVERSAL_KEY,
              transfer.committed().transactionId(),
              "duplicate");

      assertReplayOf(fundingReplay, funding);
      assertReplayOf(transferReplay, transfer);
      assertReplayOf(reversalReplay, reversal);
      assertThat(f.reconciliation.reconcile().transactionCount()).isEqualTo(before);
      assertThat(f.balance(alice.walletId())).isZero();
      assertThat(f.balance(bob.walletId())).isEqualTo(1_000);

      assertReused(
          () -> f.funding.fund(ServiceFixture.ADMIN, FUND_KEY, alice.walletId(), 999, "INR"));
      assertReused(
          () ->
              f.transfers.transfer(
                  aliceAgain,
                  TRANSFER_KEY,
                  new TransferRequest(alice.walletId(), bob.walletId(), 601, "INR", null)));
      assertReused(
          () ->
              f.reversals.reverse(
                  ServiceFixture.ADMIN,
                  REVERSAL_KEY,
                  transfer.committed().transactionId(),
                  "another reason"));
      assertThat(f.reconciliation.reconcile().transactionCount()).isEqualTo(before);
      assertThat(f.reconciliation.reconcile().consistent()).isTrue();
    }
  }

  private static void assertReplayOf(MoneyResult replay, MoneyResult original) {
    assertThat(original.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.committed()).isNull();
    assertThat(replay.status()).isEqualTo(original.status());
    assertThat(replay.body()).isEqualTo(original.body());
  }

  private static void assertReused(Runnable call) {
    assertThatThrownBy(call::run)
        .isInstanceOf(LedgerException.class)
        .extracting(e -> ((LedgerException) e).code())
        .isEqualTo(ErrorCode.IDEMPOTENCY_KEY_REUSED);
  }
}
