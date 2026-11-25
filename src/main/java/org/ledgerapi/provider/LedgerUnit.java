package org.ledgerapi.provider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.ledgerapi.domain.Account;
import org.ledgerapi.domain.Entry;
import org.ledgerapi.domain.IdempotencyRecord;
import org.ledgerapi.domain.LedgerTransaction;
import org.ledgerapi.domain.Session;
import org.ledgerapi.domain.User;
import org.ledgerapi.domain.Wallet;

/**
 * Repository operations available inside one storage transaction. Mutating methods are only valid
 * inside {@link LedgerStore#write}.
 */
public interface LedgerUnit {
  // Users and sessions

  Optional<User> findUser(String userId);

  Optional<User> findUserByLogin(String loginName);

  void insertUser(User user);

  void insertSession(Session session);

  Optional<Session> findSessionByTokenHash(String tokenHash);

  void revokeSession(String sessionId, Instant revokedAt);

  int revokeAllSessions(String userId, Instant revokedAt);

  // Currencies, accounts and wallets

  boolean isCurrencySupported(String currency);

  Optional<Account> findAccount(String accountId);

  Optional<Account> findSystemAccount(String currency);

  Optional<Wallet> findWallet(String walletId);

  Optional<Wallet> findWalletByUserAndCurrency(String userId, String currency);

  List<Wallet> walletsOfUser(String userId);

  /** Inserts the wallet's zero-balance WALLET account and the wallet row together (FR-1.4). */
  void insertWallet(Wallet wallet);

  void updateBalance(String accountId, long balance);

  // Ledger

  LedgerHead head();

  void insertTransaction(LedgerTransaction transaction);

  Optional<LedgerTransaction> findTransaction(String transactionId);

  /** Identifier of the reversal of {@code transactionId}, if any (derived {@code reversedBy}). */
  Optional<String> findReversalOf(String transactionId);

  /** Entries of {@code accountId} newest first, with entry number below {@code before}. */
  List<Entry> entriesOf(String accountId, long before, int limit);

  /** True when one of the transaction's entries is on a wallet owned by {@code userId}. */
  boolean isTransactionVisibleTo(String transactionId, String userId);

  // Idempotency

  Optional<IdempotencyRecord> findIdempotency(String principalId, String idempotencyKey);

  void insertIdempotency(IdempotencyRecord record);

  // Reconciliation

  /** Every account, transaction and entry, read leniently so corrupt rows can be reported. */
  LedgerSnapshot snapshot();

  /** Last committed sequence, its hash and the last entry number (zero and genesis when empty). */
  record LedgerHead(long lastSequence, String lastHash, long lastEntryNumber) {}
}
