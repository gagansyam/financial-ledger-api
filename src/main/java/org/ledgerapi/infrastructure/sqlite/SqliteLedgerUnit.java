package org.ledgerapi.infrastructure.sqlite;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.ledgerapi.domain.Account;
import org.ledgerapi.domain.AccountKind;
import org.ledgerapi.domain.Direction;
import org.ledgerapi.domain.Entry;
import org.ledgerapi.domain.IdempotencyRecord;
import org.ledgerapi.domain.LedgerTransaction;
import org.ledgerapi.domain.Session;
import org.ledgerapi.domain.Timestamps;
import org.ledgerapi.domain.TransactionHasher;
import org.ledgerapi.domain.TransactionType;
import org.ledgerapi.domain.User;
import org.ledgerapi.domain.Wallet;
import org.ledgerapi.provider.LedgerSnapshot;
import org.ledgerapi.provider.LedgerSnapshot.AccountRow;
import org.ledgerapi.provider.LedgerSnapshot.EntryRow;
import org.ledgerapi.provider.LedgerSnapshot.TransactionRow;
import org.ledgerapi.provider.LedgerUnit;

/**
 * Repository operations on one connection inside one SQLite transaction. Every statement uses bound
 * parameters (NFR-SEC-6); SQL failures are translated to storage exceptions.
 */
final class SqliteLedgerUnit implements LedgerUnit {
  private static final String TX_COLUMNS =
      "transaction_id, sequence, type, source_account_id, destination_account_id, amount,"
          + " currency, reference, reason, reversal_of, created_at, previous_hash, hash";
  private static final String ENTRY_COLUMNS =
      "entry_number, transaction_id, account_id, direction, amount, currency, created_at";

  private final Connection connection;

  SqliteLedgerUnit(Connection connection) {
    this.connection = connection;
  }

  @FunctionalInterface
  private interface Binder {
    void bind(PreparedStatement statement) throws SQLException;
  }

  @FunctionalInterface
  private interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
  }

  private <T> List<T> query(String sql, Binder binder, RowMapper<T> mapper) {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      binder.bind(statement);
      try (ResultSet rs = statement.executeQuery()) {
        List<T> rows = new ArrayList<>();
        while (rs.next()) {
          rows.add(mapper.map(rs));
        }
        return rows;
      }
    } catch (SQLException e) {
      throw SqliteDatabase.translate(e);
    }
  }

  private <T> Optional<T> queryOne(String sql, Binder binder, RowMapper<T> mapper) {
    List<T> rows = query(sql, binder, mapper);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
  }

  private int update(String sql, Binder binder) {
    try (PreparedStatement statement = connection.prepareStatement(sql)) {
      binder.bind(statement);
      return statement.executeUpdate();
    } catch (SQLException e) {
      throw SqliteDatabase.translate(e);
    }
  }

  private static void setNullable(PreparedStatement s, int index, String value)
      throws SQLException {
    if (value == null) {
      s.setNull(index, Types.VARCHAR);
    } else {
      s.setString(index, value);
    }
  }

  // Users and sessions

  private static User user(ResultSet rs) throws SQLException {
    return new User(
        rs.getString("user_id"),
        rs.getString("login_name"),
        rs.getString("display_name"),
        rs.getString("password_hash"),
        rs.getString("created_at"));
  }

  @Override
  public Optional<User> findUser(String userId) {
    return queryOne(
        "SELECT user_id, login_name, display_name, password_hash, created_at FROM app_user"
            + " WHERE user_id = ?",
        s -> s.setString(1, userId),
        SqliteLedgerUnit::user);
  }

  @Override
  public Optional<User> findUserByLogin(String loginName) {
    return queryOne(
        "SELECT user_id, login_name, display_name, password_hash, created_at FROM app_user"
            + " WHERE login_name = ?",
        s -> s.setString(1, loginName),
        SqliteLedgerUnit::user);
  }

  @Override
  public void insertUser(User user) {
    update(
        "INSERT INTO app_user (user_id, login_name, display_name, password_hash, role,"
            + " created_at) VALUES (?, ?, ?, ?, 'USER', ?)",
        s -> {
          s.setString(1, user.userId());
          s.setString(2, user.loginName());
          s.setString(3, user.displayName());
          s.setString(4, user.passwordHash());
          s.setString(5, user.createdAt());
        });
  }

  @Override
  public void insertSession(Session session) {
    update(
        "INSERT INTO user_session (session_id, user_id, token_hash, created_at, expires_at,"
            + " revoked_at) VALUES (?, ?, ?, ?, ?, NULL)",
        s -> {
          s.setString(1, session.sessionId());
          s.setString(2, session.userId());
          s.setString(3, session.tokenHash());
          s.setString(4, Timestamps.format(session.createdAt()));
          s.setString(5, Timestamps.format(session.expiresAt()));
        });
  }

  @Override
  public Optional<Session> findSessionByTokenHash(String tokenHash) {
    return queryOne(
        "SELECT session_id, user_id, token_hash, created_at, expires_at, revoked_at"
            + " FROM user_session WHERE token_hash = ?",
        s -> s.setString(1, tokenHash),
        rs -> {
          String revoked = rs.getString("revoked_at");
          return new Session(
              rs.getString("session_id"),
              rs.getString("user_id"),
              rs.getString("token_hash"),
              Timestamps.parse(rs.getString("created_at")),
              Timestamps.parse(rs.getString("expires_at")),
              revoked == null ? null : Timestamps.parse(revoked));
        });
  }

  @Override
  public void revokeSession(String sessionId, Instant revokedAt) {
    update(
        "UPDATE user_session SET revoked_at = ? WHERE session_id = ? AND revoked_at IS NULL",
        s -> {
          s.setString(1, Timestamps.format(revokedAt));
          s.setString(2, sessionId);
        });
  }

  @Override
  public int revokeAllSessions(String userId, Instant revokedAt) {
    return update(
        "UPDATE user_session SET revoked_at = ? WHERE user_id = ? AND revoked_at IS NULL",
        s -> {
          s.setString(1, Timestamps.format(revokedAt));
          s.setString(2, userId);
        });
  }

  // Currencies, accounts and wallets

  @Override
  public boolean isCurrencySupported(String currency) {
    return queryOne(
            "SELECT 1 FROM currency WHERE code = ?", s -> s.setString(1, currency), rs -> true)
        .isPresent();
  }

  private static Account account(ResultSet rs) throws SQLException {
    return new Account(
        rs.getString("account_id"),
        AccountKind.valueOf(rs.getString("kind")),
        rs.getString("currency"),
        rs.getLong("balance"));
  }

  @Override
  public Optional<Account> findAccount(String accountId) {
    return queryOne(
        "SELECT account_id, kind, currency, balance FROM account WHERE account_id = ?",
        s -> s.setString(1, accountId),
        SqliteLedgerUnit::account);
  }

  @Override
  public Optional<Account> findSystemAccount(String currency) {
    return queryOne(
        "SELECT account_id, kind, currency, balance FROM account"
            + " WHERE kind = 'SYSTEM' AND currency = ?",
        s -> s.setString(1, currency),
        SqliteLedgerUnit::account);
  }

  private static final String WALLET_SELECT =
      "SELECT w.wallet_id, w.user_id, w.currency, a.balance, w.created_at FROM wallet w"
          + " JOIN account a ON a.account_id = w.wallet_id";

  private static Wallet wallet(ResultSet rs) throws SQLException {
    return new Wallet(
        rs.getString("wallet_id"),
        rs.getString("user_id"),
        rs.getString("currency"),
        rs.getLong("balance"),
        rs.getString("created_at"));
  }

  @Override
  public Optional<Wallet> findWallet(String walletId) {
    return queryOne(
        WALLET_SELECT + " WHERE w.wallet_id = ?",
        s -> s.setString(1, walletId),
        SqliteLedgerUnit::wallet);
  }

  @Override
  public Optional<Wallet> findWalletByUserAndCurrency(String userId, String currency) {
    return queryOne(
        WALLET_SELECT + " WHERE w.user_id = ? AND w.currency = ?",
        s -> {
          s.setString(1, userId);
          s.setString(2, currency);
        },
        SqliteLedgerUnit::wallet);
  }

  @Override
  public List<Wallet> walletsOfUser(String userId) {
    return query(
        WALLET_SELECT + " WHERE w.user_id = ? ORDER BY w.created_at, w.wallet_id",
        s -> s.setString(1, userId),
        SqliteLedgerUnit::wallet);
  }

  @Override
  public void insertWallet(Wallet wallet) {
    update(
        "INSERT INTO account (account_id, kind, currency, balance, created_at)"
            + " VALUES (?, 'WALLET', ?, 0, ?)",
        s -> {
          s.setString(1, wallet.walletId());
          s.setString(2, wallet.currency());
          s.setString(3, wallet.createdAt());
        });
    update(
        "INSERT INTO wallet (wallet_id, user_id, currency, created_at) VALUES (?, ?, ?, ?)",
        s -> {
          s.setString(1, wallet.walletId());
          s.setString(2, wallet.userId());
          s.setString(3, wallet.currency());
          s.setString(4, wallet.createdAt());
        });
  }

  @Override
  public void updateBalance(String accountId, long balance) {
    int rows =
        update(
            "UPDATE account SET balance = ? WHERE account_id = ?",
            s -> {
              s.setLong(1, balance);
              s.setString(2, accountId);
            });
    if (rows != 1) {
      throw new IllegalStateException("Balance update matched " + rows + " accounts");
    }
  }

  // Ledger

  @Override
  public LedgerHead head() {
    LedgerHead empty = new LedgerHead(0, TransactionHasher.GENESIS_HASH, 0);
    LedgerHead lastTx =
        queryOne(
                "SELECT sequence, hash FROM ledger_transaction ORDER BY sequence DESC LIMIT 1",
                s -> {},
                rs -> new LedgerHead(rs.getLong(1), rs.getString(2), 0))
            .orElse(empty);
    long lastEntry =
        queryOne(
                "SELECT COALESCE(MAX(entry_number), 0) FROM ledger_entry",
                s -> {},
                rs -> rs.getLong(1))
            .orElse(0L);
    return new LedgerHead(lastTx.lastSequence(), lastTx.lastHash(), lastEntry);
  }

  @Override
  public void insertTransaction(LedgerTransaction tx) {
    update(
        "INSERT INTO ledger_transaction (" + TX_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
        s -> {
          s.setString(1, tx.transactionId());
          s.setLong(2, tx.sequence());
          s.setString(3, tx.type().name());
          s.setString(4, tx.sourceAccountId());
          s.setString(5, tx.destinationAccountId());
          s.setLong(6, tx.amount());
          s.setString(7, tx.currency());
          setNullable(s, 8, tx.reference());
          setNullable(s, 9, tx.reason());
          setNullable(s, 10, tx.reversalOf());
          s.setString(11, tx.createdAt());
          s.setString(12, tx.previousHash());
          s.setString(13, tx.hash());
        });
    for (Entry entry : tx.entries()) {
      update(
          "INSERT INTO ledger_entry (" + ENTRY_COLUMNS + ") VALUES (?,?,?,?,?,?,?)",
          s -> {
            s.setLong(1, entry.entryNumber());
            s.setString(2, entry.transactionId());
            s.setString(3, entry.accountId());
            s.setString(4, entry.direction().name());
            s.setLong(5, entry.amount());
            s.setString(6, entry.currency());
            s.setString(7, entry.createdAt());
          });
    }
  }

  private static Entry entry(ResultSet rs) throws SQLException {
    return new Entry(
        rs.getLong("entry_number"),
        rs.getString("transaction_id"),
        rs.getString("account_id"),
        Direction.valueOf(rs.getString("direction")),
        rs.getLong("amount"),
        rs.getString("currency"),
        rs.getString("created_at"));
  }

  @Override
  public Optional<LedgerTransaction> findTransaction(String transactionId) {
    List<Entry> entries =
        query(
            "SELECT "
                + ENTRY_COLUMNS
                + " FROM ledger_entry WHERE transaction_id = ?"
                + " ORDER BY entry_number",
            s -> s.setString(1, transactionId),
            SqliteLedgerUnit::entry);
    return queryOne(
        "SELECT " + TX_COLUMNS + " FROM ledger_transaction WHERE transaction_id = ?",
        s -> s.setString(1, transactionId),
        rs ->
            new LedgerTransaction(
                rs.getString("transaction_id"),
                rs.getLong("sequence"),
                TransactionType.valueOf(rs.getString("type")),
                rs.getString("source_account_id"),
                rs.getString("destination_account_id"),
                rs.getLong("amount"),
                rs.getString("currency"),
                rs.getString("reference"),
                rs.getString("reason"),
                rs.getString("reversal_of"),
                rs.getString("created_at"),
                rs.getString("previous_hash"),
                rs.getString("hash"),
                entries));
  }

  @Override
  public Optional<String> findReversalOf(String transactionId) {
    return queryOne(
        "SELECT transaction_id FROM ledger_transaction WHERE reversal_of = ?",
        s -> s.setString(1, transactionId),
        rs -> rs.getString(1));
  }

  @Override
  public List<Entry> entriesOf(String accountId, long before, int limit) {
    return query(
        "SELECT "
            + ENTRY_COLUMNS
            + " FROM ledger_entry WHERE account_id = ? AND entry_number < ?"
            + " ORDER BY entry_number DESC LIMIT ?",
        s -> {
          s.setString(1, accountId);
          s.setLong(2, before);
          s.setInt(3, limit);
        },
        SqliteLedgerUnit::entry);
  }

  @Override
  public boolean isTransactionVisibleTo(String transactionId, String userId) {
    return queryOne(
            "SELECT 1 FROM ledger_entry e JOIN wallet w ON w.wallet_id = e.account_id"
                + " WHERE e.transaction_id = ? AND w.user_id = ? LIMIT 1",
            s -> {
              s.setString(1, transactionId);
              s.setString(2, userId);
            },
            rs -> true)
        .isPresent();
  }

  // Idempotency

  @Override
  public Optional<IdempotencyRecord> findIdempotency(String principalId, String idempotencyKey) {
    return queryOne(
        "SELECT principal_id, idempotency_key, fingerprint, response_status, response_body,"
            + " transaction_id, created_at FROM idempotency_record"
            + " WHERE principal_id = ? AND idempotency_key = ?",
        s -> {
          s.setString(1, principalId);
          s.setString(2, idempotencyKey);
        },
        rs ->
            new IdempotencyRecord(
                rs.getString(1),
                rs.getString(2),
                rs.getString(3),
                rs.getInt(4),
                rs.getString(5),
                rs.getString(6),
                rs.getString(7)));
  }

  @Override
  public void insertIdempotency(IdempotencyRecord record) {
    update(
        "INSERT INTO idempotency_record (principal_id, idempotency_key, fingerprint,"
            + " response_status, response_body, transaction_id, created_at)"
            + " VALUES (?, ?, ?, ?, ?, ?, ?)",
        s -> {
          s.setString(1, record.principalId());
          s.setString(2, record.idempotencyKey());
          s.setString(3, record.fingerprint());
          s.setInt(4, record.responseStatus());
          s.setString(5, record.responseBody());
          s.setString(6, record.transactionId());
          s.setString(7, record.createdAt());
        });
  }

  // Reconciliation

  /** Reads an INTEGER column, or null when the stored value is not a 64-bit integer. */
  private static Long integerOrNull(ResultSet rs, String column, String typeColumn)
      throws SQLException {
    if (!"integer".equals(rs.getString(typeColumn))) {
      return null;
    }
    return rs.getLong(column);
  }

  @Override
  public LedgerSnapshot snapshot() {
    List<String> currencies =
        query("SELECT code FROM currency ORDER BY code", s -> {}, rs -> rs.getString(1));
    List<AccountRow> accounts =
        query(
            "SELECT account_id, kind, currency, balance, typeof(balance) AS balance_type"
                + " FROM account ORDER BY account_id",
            s -> {},
            rs ->
                new AccountRow(
                    rs.getString("account_id"),
                    rs.getString("kind"),
                    rs.getString("currency"),
                    integerOrNull(rs, "balance", "balance_type")));
    List<TransactionRow> transactions =
        query(
            "SELECT "
                + TX_COLUMNS
                + ", typeof(sequence) AS sequence_type, typeof(amount) AS amount_type"
                + " FROM ledger_transaction ORDER BY sequence, transaction_id",
            s -> {},
            rs ->
                new TransactionRow(
                    rs.getString("transaction_id"),
                    integerOrNull(rs, "sequence", "sequence_type"),
                    rs.getString("type"),
                    rs.getString("source_account_id"),
                    rs.getString("destination_account_id"),
                    integerOrNull(rs, "amount", "amount_type"),
                    rs.getString("currency"),
                    rs.getString("reference"),
                    rs.getString("reason"),
                    rs.getString("reversal_of"),
                    rs.getString("created_at"),
                    rs.getString("previous_hash"),
                    rs.getString("hash")));
    List<EntryRow> entries =
        query(
            "SELECT "
                + ENTRY_COLUMNS
                + ", typeof(entry_number) AS entry_number_type, typeof(amount) AS amount_type"
                + " FROM ledger_entry ORDER BY entry_number",
            s -> {},
            rs ->
                new EntryRow(
                    integerOrNull(rs, "entry_number", "entry_number_type"),
                    rs.getString("transaction_id"),
                    rs.getString("account_id"),
                    rs.getString("direction"),
                    integerOrNull(rs, "amount", "amount_type"),
                    rs.getString("currency"),
                    rs.getString("created_at")));
    return new LedgerSnapshot(currencies, accounts, transactions, entries);
  }
}
