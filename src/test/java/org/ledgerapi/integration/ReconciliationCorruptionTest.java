package org.ledgerapi.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ledgerapi.application.ReconciliationService.Discrepancy;
import org.ledgerapi.application.ReconciliationService.Report;
import org.ledgerapi.testing.ServiceFixture;
import org.ledgerapi.testing.ServiceFixture.Holder;

/**
 * Each reconciliation check detects the corruption it is meant for (FR-5.1 to FR-5.4, TQ-5). The
 * database is tampered with outside the API, bypassing the append-only triggers on purpose.
 */
class ReconciliationCorruptionTest {
  @TempDir Path dir;
  private Path file;
  private String alice;
  private String fundingId;
  private String transferId;
  private String reversalId;

  @BeforeEach
  void buildLedger() {
    file = dir.resolve("ledger.db");
    try (ServiceFixture f = new ServiceFixture(file)) {
      Holder a = f.holder("alice");
      Holder b = f.holder("bob");
      alice = a.walletId();
      fundingId = f.fund(a.walletId(), 100).committed().transactionId();
      transferId = f.transfer(a, b.walletId(), 30, "transfer-key-0001").committed().transactionId();
      reversalId =
          f.reversals
              .reverse(ServiceFixture.ADMIN, "reversal-key-0001", transferId, "duplicate")
              .committed()
              .transactionId();
      assertThat(f.reconciliation.reconcile().consistent()).isTrue();
    }
  }

  private void tamper(String... sql) throws SQLException {
    try (Connection c = DriverManager.getConnection("jdbc:sqlite:" + file);
        Statement s = c.createStatement()) {
      s.execute("PRAGMA foreign_keys = OFF");
      s.execute("PRAGMA ignore_check_constraints = ON");
      for (String statement : sql) {
        s.execute(statement);
      }
    }
  }

  private Report reconcile() {
    try (ServiceFixture f = new ServiceFixture(file)) {
      return f.reconciliation.reconcile();
    }
  }

  private static List<String> checks(Report report) {
    return report.discrepancies().stream().map(Discrepancy::check).distinct().toList();
  }

  @Test
  void cleanLedgerIsConsistent() {
    Report report = reconcile();
    assertThat(report.consistent()).isTrue();
    assertThat(report.transactionCount()).isEqualTo(3);
    assertThat(report.entryCount()).isEqualTo(6);
    assertThat(report.toBody()).containsEntry("status", "CONSISTENT");
  }

  @Test
  void c1AndC2DetectAMissingEntry() throws SQLException {
    tamper(
        "DROP TRIGGER trg_ledger_entry_no_delete",
        "DELETE FROM ledger_entry WHERE entry_number = (SELECT max(entry_number) FROM ledger_entry)");
    Report report = reconcile();
    assertThat(report.consistent()).isFalse();
    assertThat(checks(report)).contains("C1", "C2");
    assertThat(report.toBody()).containsEntry("status", "INCONSISTENT");
  }

  @Test
  void c3DetectsACachedBalanceThatDisagreesWithEntries() throws SQLException {
    tamper("UPDATE account SET balance = balance + 1 WHERE account_id = '" + alice + "'");
    assertThat(checks(reconcile())).contains("C3", "C5");
  }

  @Test
  void c4DetectsANegativeWalletBalance() throws SQLException {
    tamper("UPDATE account SET balance = -5 WHERE account_id = '" + alice + "'");
    assertThat(checks(reconcile())).contains("C4");
  }

  @Test
  void c5DetectsAMissingSystemAccount() throws SQLException {
    tamper(
        "DROP TRIGGER trg_account_identity_immutable",
        "UPDATE account SET kind = 'WALLET', balance = 0 WHERE kind = 'SYSTEM'");
    assertThat(checks(reconcile())).contains("C5");
  }

  @Test
  void c6DetectsAnEditedTransaction() throws SQLException {
    tamper(
        "DROP TRIGGER trg_ledger_transaction_no_update",
        "UPDATE ledger_transaction SET reference = 'edited' WHERE transaction_id = '"
            + transferId
            + "'");
    Report report = reconcile();
    assertThat(checks(report)).containsExactly("C6");
    assertThat(report.discrepancies().get(0).subjectId()).isEqualTo(transferId);
  }

  @Test
  void c6DetectsASequenceGap() throws SQLException {
    tamper(
        "DROP TRIGGER trg_ledger_transaction_no_update",
        "UPDATE ledger_transaction SET sequence = 9 WHERE transaction_id = '" + reversalId + "'");
    assertThat(checks(reconcile())).contains("C6");
  }

  @Test
  void c7DetectsAReversalPointingAtTheWrongOriginal() throws SQLException {
    tamper(
        "DROP TRIGGER trg_ledger_transaction_no_update",
        "UPDATE ledger_transaction SET reversal_of = '"
            + fundingId
            + "' WHERE transaction_id = '"
            + reversalId
            + "'");
    assertThat(checks(reconcile())).contains("C7");
  }
}
