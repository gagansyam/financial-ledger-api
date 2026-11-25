package org.ledgerapi.application;

import java.util.List;
import java.util.Objects;
import org.ledgerapi.domain.Entry;
import org.ledgerapi.domain.ErrorCode;
import org.ledgerapi.domain.LedgerException;
import org.ledgerapi.domain.Principal;
import org.ledgerapi.domain.Timestamps;
import org.ledgerapi.domain.Wallet;
import org.ledgerapi.provider.ClockProvider;
import org.ledgerapi.provider.IdGenerator;
import org.ledgerapi.provider.LedgerStore;

/** Wallet creation and reads (FR-1.2 to FR-1.5, FR-4.6). */
public final class WalletService {
  public static final int DEFAULT_PAGE_SIZE = 25;
  public static final int MAX_PAGE_SIZE = 100;

  private final LedgerStore store;
  private final ClockProvider clock;
  private final IdGenerator ids;

  public WalletService(LedgerStore store, ClockProvider clock, IdGenerator ids) {
    this.store = Objects.requireNonNull(store, "store");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  /** One page of wallet entries, newest first; {@code nextBefore} is null on the last page. */
  public record EntryPage(List<Entry> items, Long nextBefore) {
    public EntryPage {
      items = List.copyOf(items);
    }
  }

  public Wallet create(Principal principal, String userId, String currency) {
    return store.write(
        unit -> {
          unit.findUser(userId).orElseThrow(() -> Problems.notFound("User"));
          if (!principal.isAdmin() && !principal.id().equals(userId)) {
            throw Problems.forbidden();
          }
          if (!unit.isCurrencySupported(currency)) {
            throw Problems.unsupportedCurrency();
          }
          if (unit.findWalletByUserAndCurrency(userId, currency).isPresent()) {
            throw new LedgerException(
                ErrorCode.WALLET_ALREADY_EXISTS, "User already has a wallet in this currency");
          }
          Wallet wallet =
              new Wallet(ids.newId(), userId, currency, 0, Timestamps.format(clock.now()));
          unit.insertWallet(wallet);
          return wallet;
        });
  }

  public Wallet get(Principal principal, String walletId) {
    return store.read(
        unit -> {
          Wallet wallet = unit.findWallet(walletId).orElseThrow(() -> Problems.notFound("Wallet"));
          requireOwner(principal, wallet);
          return wallet;
        });
  }

  /**
   * Entries of a wallet below the exclusive {@code before} cursor (null for the newest), at most
   * {@code limit} items.
   */
  public EntryPage entries(Principal principal, String walletId, Long before, int limit) {
    if (limit < 1 || limit > MAX_PAGE_SIZE) {
      throw new IllegalArgumentException("limit outside 1.." + MAX_PAGE_SIZE);
    }
    long cursor = before == null ? Long.MAX_VALUE : before;
    return store.read(
        unit -> {
          Wallet wallet = unit.findWallet(walletId).orElseThrow(() -> Problems.notFound("Wallet"));
          requireOwner(principal, wallet);
          List<Entry> rows = unit.entriesOf(walletId, cursor, limit + 1);
          if (rows.size() <= limit) {
            return new EntryPage(rows, null);
          }
          List<Entry> page = rows.subList(0, limit);
          return new EntryPage(page, page.get(limit - 1).entryNumber());
        });
  }

  private static void requireOwner(Principal principal, Wallet wallet) {
    if (!principal.isAdmin() && !principal.id().equals(wallet.userId())) {
      throw Problems.forbidden();
    }
  }
}
