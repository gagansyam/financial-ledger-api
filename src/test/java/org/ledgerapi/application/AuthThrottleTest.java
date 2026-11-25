package org.ledgerapi.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.ledgerapi.testing.FixedClock;

/** Sliding-window authentication throttle with bounded memory (NFR-SEC-9). */
class AuthThrottleTest {
  private final FixedClock clock = new FixedClock(Instant.parse("2026-01-01T00:00:00Z"));

  @Test
  void loginNameLimitAndRetryAfter() {
    AuthThrottle throttle = new AuthThrottle(clock);
    for (int i = 0; i < 5; i++) {
      throttle.checkLogin("amy", "1.1.1.1");
      throttle.recordLoginFailure("amy", "1.1.1.1");
      clock.set(clock.now().plusSeconds(60));
    }
    assertThatThrownBy(() -> throttle.checkLogin("amy", "9.9.9.9"))
        .isInstanceOfSatisfying(
            TooManyRequestsException.class, e -> assertThat(e.retryAfterSeconds()).isEqualTo(600));
    throttle.checkLogin("bob", "9.9.9.9");
    clock.set(clock.now().plus(Duration.ofMinutes(10)));
    throttle.checkLogin("amy", "9.9.9.9");
  }

  @Test
  void ipLimitCoversManyNames() {
    AuthThrottle throttle = new AuthThrottle(clock);
    for (int i = 0; i < 20; i++) {
      throttle.recordLoginFailure("n" + i, "2.2.2.2");
    }
    assertThatThrownBy(() -> throttle.checkLogin("fresh", "2.2.2.2"))
        .isInstanceOf(TooManyRequestsException.class);
  }

  @Test
  void registrationsPerIp() {
    AuthThrottle throttle = new AuthThrottle(clock);
    for (int i = 0; i < 10; i++) {
      throttle.acquireRegistration("3.3.3.3");
    }
    assertThatThrownBy(() -> throttle.acquireRegistration("3.3.3.3"))
        .isInstanceOf(TooManyRequestsException.class);
    throttle.acquireRegistration("4.4.4.4");
  }

  @Test
  void trackedKeysAreBounded() {
    AuthThrottle throttle = new AuthThrottle(clock, 50);
    for (int i = 0; i < 500; i++) {
      throttle.recordLoginFailure("name" + i, "5.5.5.5");
    }
    assertThat(throttle.trackedLoginNames()).isLessThanOrEqualTo(50);
  }
}
