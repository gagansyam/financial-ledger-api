package org.ledgerapi.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

/** Posting rules and their check order (BR-5, BR-7, FR-2.8, NFR-REL-3). */
class LedgerRulesTest {
  private static Account wallet(long balance) {
    return new Account("w", AccountKind.WALLET, "INR", balance);
  }

  private static Account system(long balance) {
    return new Account("s", AccountKind.SYSTEM, "INR", balance);
  }

  private static ErrorCode failure(Account debited, Account credited, long amount) {
    try {
      LedgerRules.apply(debited, credited, amount);
    } catch (LedgerException e) {
      return e.code();
    }
    return null;
  }

  @Test
  void amountRange() {
    assertThat(LedgerRules.isValidAmount(0)).isFalse();
    assertThat(LedgerRules.isValidAmount(1)).isTrue();
    assertThat(LedgerRules.isValidAmount(LedgerRules.MAX_AMOUNT)).isTrue();
    assertThat(LedgerRules.isValidAmount(LedgerRules.MAX_AMOUNT + 1)).isFalse();
    assertThat(failure(wallet(5), wallet(0), 0)).isEqualTo(ErrorCode.INVALID_AMOUNT);
  }

  @Test
  void walletsCannotGoNegativeButSystemCan() {
    assertThat(failure(wallet(4), wallet(0), 5)).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
    LedgerRules.Postings p = LedgerRules.apply(system(0), wallet(0), 5);
    assertThat(p.debitedBalance()).isEqualTo(-5);
    assertThat(p.creditedBalance()).isEqualTo(5);
    assertThat(LedgerRules.apply(wallet(5), wallet(0), 5).debitedBalance()).isZero();
  }

  @Test
  void walletCreditLimitIsTwoToTheFiftyThreeMinusOne() {
    long max = LedgerRules.MAX_WALLET_BALANCE;
    assertThat(LedgerRules.apply(system(0), wallet(max - 1), 1).creditedBalance()).isEqualTo(max);
    assertThat(failure(system(0), wallet(max), 1)).isEqualTo(ErrorCode.BALANCE_LIMIT_EXCEEDED);
    assertThat(failure(wallet(0), wallet(max), 1)).isEqualTo(ErrorCode.INSUFFICIENT_FUNDS);
  }

  @Test
  void systemOverflowIsANumericLimit() {
    assertThat(failure(system(Long.MIN_VALUE), wallet(0), 1))
        .isEqualTo(ErrorCode.NUMERIC_LIMIT_EXCEEDED);
    assertThat(failure(wallet(5), system(Long.MAX_VALUE), 1))
        .isEqualTo(ErrorCode.NUMERIC_LIMIT_EXCEEDED);
  }

  @Test
  void moneyRejectsInvalidAmounts() {
    assertThatThrownBy(() -> Money.transactionAmount(0, CurrencyCode.of("INR")))
        .isInstanceOf(RuntimeException.class);
  }
}
