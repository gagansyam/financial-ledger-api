package org.ledgerapi.integration;

import java.nio.file.Path;
import org.ledgerapi.testing.ServiceFixture;
import org.ledgerapi.testing.ServiceFixture.Holder;

/**
 * Child process for {@link CrashRecoveryTest}: posts transfers until it is killed. It prints the
 * wallets once, then {@code begin <key>} before and {@code done <key>} after each commit.
 */
public final class CrashWorker {
  private CrashWorker() {}

  public static void main(String[] args) {
    ServiceFixture f = ServiceFixture.randomIds(Path.of(args[0]));
    Holder a = f.holder("alice");
    Holder b = f.holder("bob");
    f.fund(a.walletId(), 1_000_000);
    System.out.println("wallets " + a.principal().id() + " " + a.walletId() + " " + b.walletId());
    System.out.flush();
    for (long n = 0; ; n++) {
      String key = String.format("crash-key-%010d", n);
      System.out.println("begin " + key);
      System.out.flush();
      f.transfer(a, b.walletId(), 1, key);
      System.out.println("done " + key);
      System.out.flush();
    }
  }
}
