package org.ledgerapi;

import io.javalin.Javalin;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import org.ledgerapi.application.AuthService;
import org.ledgerapi.application.AuthService.AdminCredential;
import org.ledgerapi.application.AuthThrottle;
import org.ledgerapi.application.BuildInfo;
import org.ledgerapi.application.FundingService;
import org.ledgerapi.application.HealthService;
import org.ledgerapi.application.IdempotencyGuard;
import org.ledgerapi.application.LedgerServices;
import org.ledgerapi.application.ReconciliationService;
import org.ledgerapi.application.ReversalService;
import org.ledgerapi.application.TransactionPoster;
import org.ledgerapi.application.TransactionService;
import org.ledgerapi.application.TransferService;
import org.ledgerapi.application.UserService;
import org.ledgerapi.application.WalletService;
import org.ledgerapi.config.AppConfig;
import org.ledgerapi.config.AppConfig.AdminKey;
import org.ledgerapi.config.AppConfig.CurrencySpec;
import org.ledgerapi.http.HttpApi;
import org.ledgerapi.infrastructure.sqlite.SchemaMigrator;
import org.ledgerapi.infrastructure.sqlite.SqliteDatabase;
import org.ledgerapi.infrastructure.sqlite.SqliteLedgerStore;
import org.ledgerapi.infrastructure.system.Argon2idPasswordHasher;
import org.ledgerapi.infrastructure.system.RandomUuidGenerator;
import org.ledgerapi.infrastructure.system.SecureRandomTokenGenerator;
import org.ledgerapi.infrastructure.system.Sha256HashProvider;
import org.ledgerapi.infrastructure.system.SystemClock;
import org.ledgerapi.provider.ClockProvider;
import org.ledgerapi.provider.HashProvider;
import org.ledgerapi.provider.IdGenerator;
import org.ledgerapi.provider.LedgerStore;
import org.ledgerapi.provider.PasswordHasher;
import org.ledgerapi.provider.StorageProbe;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Composition root: wires providers, migrates storage and runs the HTTP server. */
public final class LedgerApplication implements AutoCloseable {
  private static final Logger LOG = LoggerFactory.getLogger(LedgerApplication.class);

  private final SqliteDatabase database;
  private final Javalin http;

  private LedgerApplication(SqliteDatabase database, Javalin http) {
    this.database = database;
    this.http = http;
  }

  /** Starts with real providers. */
  public static LedgerApplication start(AppConfig config) {
    return start(config, new SystemClock(), new RandomUuidGenerator(), null);
  }

  /**
   * Starts with injected providers (DC-4). A non-null {@code probeOverride} replaces the database
   * health probe, which lets tests exercise the degraded health path.
   */
  public static LedgerApplication start(
      AppConfig config, ClockProvider clock, IdGenerator ids, StorageProbe probeOverride) {
    return start(config, clock, ids, probeOverride, null);
  }

  /**
   * As above; a non-null {@code passwordsOverride} replaces Argon2id so system tests can register
   * and log in without the production hashing cost.
   */
  public static LedgerApplication start(
      AppConfig config,
      ClockProvider clock,
      IdGenerator ids,
      StorageProbe probeOverride,
      PasswordHasher passwordsOverride) {
    Objects.requireNonNull(config, "config");
    SqliteDatabase database =
        SqliteDatabase.open(config.databasePath(), config.readPoolSize(), config.lockTimeout());
    try {
      new SchemaMigrator(clock, ids).migrate(database, config.currencies());
      StorageProbe probe = probeOverride != null ? probeOverride : database;
      PasswordHasher passwords =
          passwordsOverride != null
              ? passwordsOverride
              : new Argon2idPasswordHasher(config.passwordHash());
      LedgerServices services =
          wire(
              config,
              clock,
              ids,
              new HealthService(probe, BuildInfo.version()),
              database,
              passwords);
      Javalin http = HttpApi.create(services, config.trustedProxies());
      http.start(config.listenAddress(), config.port());
      LOG.atInfo()
          .addKeyValue("port", http.port())
          .addKeyValue("version", BuildInfo.version())
          .log("started");
      return new LedgerApplication(database, http);
    } catch (RuntimeException e) {
      database.close();
      throw e;
    }
  }

  private static LedgerServices wire(
      AppConfig config,
      ClockProvider clock,
      IdGenerator ids,
      HealthService health,
      SqliteDatabase database,
      PasswordHasher passwords) {
    HashProvider hashes = new Sha256HashProvider();
    LedgerStore store = new SqliteLedgerStore(database);
    AuthThrottle throttle = new AuthThrottle(clock);
    List<AdminCredential> admins = new ArrayList<>();
    for (AdminKey key : config.adminKeys()) {
      admins.add(new AdminCredential(key.principalId(), key.keySha256Hex()));
    }
    AuthService auth =
        new AuthService(
            store,
            passwords,
            new SecureRandomTokenGenerator(),
            hashes,
            clock,
            ids,
            throttle,
            config.sessionLifetime(),
            admins);
    IdempotencyGuard guard = new IdempotencyGuard(clock, hashes);
    TransactionPoster poster = new TransactionPoster(clock, ids, hashes);
    List<String> currencies = new ArrayList<>();
    for (CurrencySpec spec : config.currencies()) {
      currencies.add(spec.code());
    }
    return new LedgerServices(
        health,
        new UserService(store, passwords, clock, ids),
        auth,
        throttle,
        new WalletService(store, clock, ids),
        new TransferService(store, guard, poster),
        new FundingService(store, guard, poster),
        new ReversalService(store, guard, poster),
        new TransactionService(store),
        new ReconciliationService(store, clock, hashes, currencies));
  }

  public int port() {
    return http.port();
  }

  /** Stops accepting requests, lets in-flight requests finish, then closes storage (FR-6.3). */
  @Override
  public void close() {
    try {
      http.stop();
    } finally {
      database.close();
      LOG.info("stopped");
    }
  }
}
