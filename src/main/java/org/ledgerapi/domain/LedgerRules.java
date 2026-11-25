package org.ledgerapi.domain;

/**
 * Balance rules for one posting that debits one account and credits another (BR-6, BR-7,
 * NFR-REL-3). Checks run in the SRS order: debited wallet funds, credited wallet limit, then the
 * remaining signed 64-bit bounds. System accounts may go negative.
 */
public final class LedgerRules {
  public static final long MAX_AMOUNT = 1_000_000_000_000L;
  public static final long MAX_WALLET_BALANCE = 9_007_199_254_740_991L;

  private LedgerRules() {}

  /** New balances after a posting. */
  public record Postings(long debitedBalance, long creditedBalance) {}

  public static boolean isValidAmount(long amount) {
    return amount >= 1 && amount <= MAX_AMOUNT;
  }

  public static Postings apply(Account debited, Account credited, long amount) {
    if (!isValidAmount(amount)) {
      throw new LedgerException(ErrorCode.INVALID_AMOUNT, "Amount is outside the allowed range");
    }
    if (debited.isWallet() && debited.balance() < amount) {
      throw new LedgerException(ErrorCode.INSUFFICIENT_FUNDS, "Insufficient funds");
    }
    if (credited.isWallet() && credited.balance() > MAX_WALLET_BALANCE - amount) {
      throw new LedgerException(
          ErrorCode.BALANCE_LIMIT_EXCEEDED, "Credit would exceed the wallet balance limit");
    }
    try {
      return new Postings(
          Math.subtractExact(debited.balance(), amount), Math.addExact(credited.balance(), amount));
    } catch (ArithmeticException e) {
      throw new LedgerException(
          ErrorCode.NUMERIC_LIMIT_EXCEEDED, "Proposed balance exceeds the numeric range");
    }
  }
}
