package org.ledgerapi.provider;

/**
 * Transactional storage port (DC-4, NFR-MNT-1). Application services receive a {@link LedgerUnit}
 * and never see connections or SQL.
 */
public interface LedgerStore {
  /** Work executed against one storage transaction. */
  @FunctionalInterface
  interface Work<T> {
    T execute(LedgerUnit unit);
  }

  /**
   * Runs {@code work} in one serialized write transaction and commits before returning. Any
   * exception rolls back every change. Throws {@link StorageBusyException} when the write lock is
   * not obtained in time.
   */
  <T> T write(Work<T> work);

  /** Runs {@code work} against one consistent read snapshot without the write lock. */
  <T> T read(Work<T> work);
}
