package org.ledgerapi.application;

import java.util.List;
import java.util.Objects;
import org.ledgerapi.domain.ErrorCode;
import org.ledgerapi.domain.LedgerException;
import org.ledgerapi.domain.Principal;
import org.ledgerapi.domain.Timestamps;
import org.ledgerapi.domain.User;
import org.ledgerapi.domain.Wallet;
import org.ledgerapi.provider.ClockProvider;
import org.ledgerapi.provider.IdGenerator;
import org.ledgerapi.provider.LedgerStore;
import org.ledgerapi.provider.PasswordHasher;

/** Registration and profile reads (FR-1.1, FR-1.6). */
public final class UserService {
  private final LedgerStore store;
  private final PasswordHasher passwords;
  private final ClockProvider clock;
  private final IdGenerator ids;

  public UserService(
      LedgerStore store, PasswordHasher passwords, ClockProvider clock, IdGenerator ids) {
    this.store = Objects.requireNonNull(store, "store");
    this.passwords = Objects.requireNonNull(passwords, "passwords");
    this.clock = Objects.requireNonNull(clock, "clock");
    this.ids = Objects.requireNonNull(ids, "ids");
  }

  /** A user with every wallet, read from one snapshot. */
  public record UserProfile(User user, List<Wallet> wallets) {
    public UserProfile {
      wallets = List.copyOf(wallets);
    }
  }

  /** Registers a USER. The slow password hash is computed before taking the write lock. */
  public User register(String displayName, String loginName, String password) {
    String passwordHash = passwords.hash(password);
    return store.write(
        unit -> {
          if (unit.findUserByLogin(loginName).isPresent()) {
            throw new LedgerException(
                ErrorCode.LOGIN_ALREADY_EXISTS, "Login name is already in use");
          }
          User user =
              new User(
                  ids.newId(),
                  loginName,
                  displayName,
                  passwordHash,
                  Timestamps.format(clock.now()));
          unit.insertUser(user);
          return user;
        });
  }

  public UserProfile get(Principal principal, String userId) {
    return store.read(
        unit -> {
          User user = unit.findUser(userId).orElseThrow(() -> Problems.notFound("User"));
          if (!principal.isAdmin() && !principal.id().equals(userId)) {
            throw Problems.forbidden();
          }
          return new UserProfile(user, unit.walletsOfUser(userId));
        });
  }
}
