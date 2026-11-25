package org.ledgerapi.testing;

import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.function.UnaryOperator;
import org.ledgerapi.application.FundingService;
import org.ledgerapi.application.IdempotencyGuard;
import org.ledgerapi.application.MoneyResult;
import org.ledgerapi.application.ReconciliationService;
import org.ledgerapi.application.ReversalService;
import org.ledgerapi.application.TransactionPoster;
import org.ledgerapi.application.TransferService;
import org.ledgerapi.application.TransferService.TransferRequest;
import org.ledgerapi.application.UserService;
import org.ledgerapi.application.WalletService;
import org.ledgerapi.config.AppConfig.CurrencySpec;
import org.ledgerapi.domain.Principal;
import org.ledgerapi.infrastructure.sqlite.SchemaMigrator;
import org.ledgerapi.infrastructure.sqlite.SqliteDatabase;
import org.ledgerapi.infrastructure.sqlite.SqliteLedgerStore;
import org.ledgerapi.infrastructure.system.RandomUuidGenerator;
import org.ledgerapi.infrastructure.system.Sha256HashProvider;
import org.ledgerapi.provider.IdGenerator;
import org.ledgerapi.provider.LedgerStore;

/** Application services over a file-backed database, without HTTP. */
public final class ServiceFixture implements AutoCloseable {
  public static final Principal ADMIN = Principal.admin("ops-admin");

  public final FixedClock clock = new FixedClock(Instant.parse("2026-03-01T10:00:00Z"));
  public final IdGenerator ids;
  public final SqliteDatabase database;
  public final LedgerStore store;
  public final UserService users;
  public final WalletService wallets;
  public final FundingService funding;
  public final TransferService transfers;
  public final ReversalService reversals;
  public final ReconciliationService reconciliation;
  private final Sha256HashProvider hashes = new Sha256HashProvider();

  public ServiceFixture(Path file) {
    this(file, UnaryOperator.identity(), new SequentialIds());
  }

  public ServiceFixture(Path file, UnaryOperator<LedgerStore> wrapMoneyStore, IdGenerator ids) {
    this(file, wrapMoneyStore, ids, Duration.ofSeconds(5));
  }

  public ServiceFixture(
      Path file, UnaryOperator<LedgerStore> wrapMoneyStore, IdGenerator ids, Duration lockTimeout) {
    this.ids = ids;
    database = SqliteDatabase.open(file, 4, lockTimeout);
    new SchemaMigrator(clock, ids).migrate(database, List.of(new CurrencySpec("INR", 2)));
    store = new SqliteLedgerStore(database);
    LedgerStore money = wrapMoneyStore.apply(store);
    IdempotencyGuard guard = new IdempotencyGuard(clock, hashes);
    TransactionPoster poster = new TransactionPoster(clock, ids, hashes);
    users = new UserService(store, new FastPasswordHasher(), clock, ids);
    wallets = new WalletService(store, clock, ids);
    funding = new FundingService(money, guard, poster);
    transfers = new TransferService(money, guard, poster);
    reversals = new ReversalService(money, guard, poster);
    reconciliation = new ReconciliationService(store, clock, hashes, List.of("INR"));
  }

  /** Opens with random identifiers, for processes that restart on the same file. */
  public static ServiceFixture randomIds(Path file) {
    return new ServiceFixture(file, UnaryOperator.identity(), new RandomUuidGenerator());
  }

  /** A user principal owning one INR wallet; returns {principal, walletId}. */
  public record Holder(Principal principal, String walletId) {}

  public Holder holder(String login) {
    String userId = users.register(login, login, "long enough pw").userId();
    Principal p = Principal.user(userId, ids.newId());
    return new Holder(p, wallets.create(p, userId, "INR").walletId());
  }

  public MoneyResult fund(String walletId, long amount) {
    return funding.fund(ADMIN, "fund-" + ids.newId().substring(24) + "-k", walletId, amount, "INR");
  }

  public MoneyResult transfer(Holder from, String to, long amount, String key) {
    return transfers.transfer(
        from.principal(), key, new TransferRequest(from.walletId(), to, amount, "INR", null));
  }

  public long balance(String walletId) {
    return wallets.get(ADMIN, walletId).balance();
  }

  @Override
  public void close() {
    database.close();
  }
}
