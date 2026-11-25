package org.ledgerapi.infrastructure.sqlite;

import java.util.Objects;
import org.ledgerapi.provider.LedgerStore;

/** {@link LedgerStore} over {@link SqliteDatabase}; each call gets a fresh repository unit. */
public final class SqliteLedgerStore implements LedgerStore {
  private final SqliteDatabase database;

  public SqliteLedgerStore(SqliteDatabase database) {
    this.database = Objects.requireNonNull(database, "database");
  }

  @Override
  public <T> T write(Work<T> work) {
    return database.write(connection -> work.execute(new SqliteLedgerUnit(connection)));
  }

  @Override
  public <T> T read(Work<T> work) {
    return database.read(connection -> work.execute(new SqliteLedgerUnit(connection)));
  }
}
