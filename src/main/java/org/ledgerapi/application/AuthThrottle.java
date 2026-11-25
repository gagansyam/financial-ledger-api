package org.ledgerapi.application;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import org.ledgerapi.provider.ClockProvider;

/**
 * Sliding-window throttle for login and registration (NFR-SEC-9). Each counter keeps at most {@code
 * limit} timestamps per key and at most {@code maxKeys} keys (least recently used keys are
 * evicted), so memory stays bounded whatever the request volume.
 */
public final class AuthThrottle {
  public static final Duration WINDOW = Duration.ofMinutes(15);
  public static final int LOGIN_FAILURES_PER_NAME = 5;
  public static final int LOGIN_FAILURES_PER_IP = 20;
  public static final int REGISTRATIONS_PER_IP = 10;
  public static final int DEFAULT_MAX_KEYS = 10_000;

  private final ClockProvider clock;
  private final Counter loginByName;
  private final Counter loginByIp;
  private final Counter registrationByIp;

  public AuthThrottle(ClockProvider clock) {
    this(clock, DEFAULT_MAX_KEYS);
  }

  public AuthThrottle(ClockProvider clock, int maxKeys) {
    this.clock = Objects.requireNonNull(clock, "clock");
    this.loginByName = new Counter(LOGIN_FAILURES_PER_NAME, maxKeys);
    this.loginByIp = new Counter(LOGIN_FAILURES_PER_IP, maxKeys);
    this.registrationByIp = new Counter(REGISTRATIONS_PER_IP, maxKeys);
  }

  /** Rejects a login attempt while either failure window is full. */
  public void checkLogin(String loginName, String ip) {
    Instant now = clock.now();
    long wait = Math.max(loginByName.waitSeconds(loginName, now), loginByIp.waitSeconds(ip, now));
    if (wait > 0) {
      throw new TooManyRequestsException(wait);
    }
  }

  public void recordLoginFailure(String loginName, String ip) {
    Instant now = clock.now();
    loginByName.record(loginName, now);
    loginByIp.record(ip, now);
  }

  /** Counts a registration request, rejecting it when the window is already full. */
  public void acquireRegistration(String ip) {
    Instant now = clock.now();
    long wait = registrationByIp.tryRecord(ip, now);
    if (wait > 0) {
      throw new TooManyRequestsException(wait);
    }
  }

  /** Per-key timestamps inside the window. */
  static final class Counter {
    private final int limit;
    private final Map<String, Deque<Instant>> events;

    Counter(int limit, int maxKeys) {
      this.limit = limit;
      this.events =
          new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, Deque<Instant>> eldest) {
              return size() > maxKeys;
            }
          };
    }

    synchronized long waitSeconds(String key, Instant now) {
      Deque<Instant> window = prune(key, now);
      if (window == null || window.size() < limit) {
        return 0;
      }
      return secondsUntil(window.peekFirst().plus(WINDOW), now);
    }

    synchronized void record(String key, Instant now) {
      Deque<Instant> window = prune(key, now);
      if (window == null) {
        window = new ArrayDeque<>();
        events.put(key, window);
      }
      window.addLast(now);
      while (window.size() > limit) {
        window.removeFirst();
      }
    }

    /** Records the event unless the window is full; returns the wait in seconds, or 0. */
    synchronized long tryRecord(String key, Instant now) {
      long wait = waitSeconds(key, now);
      if (wait == 0) {
        record(key, now);
      }
      return wait;
    }

    synchronized int size() {
      return events.size();
    }

    private Deque<Instant> prune(String key, Instant now) {
      Deque<Instant> window = events.get(key);
      if (window == null) {
        return null;
      }
      Instant cutoff = now.minus(WINDOW);
      while (!window.isEmpty() && !window.peekFirst().isAfter(cutoff)) {
        window.removeFirst();
      }
      return window;
    }

    private static long secondsUntil(Instant expiry, Instant now) {
      long millis = Duration.between(now, expiry).toMillis();
      return Math.max(1, (millis + 999) / 1000);
    }
  }

  int trackedLoginNames() {
    return loginByName.size();
  }
}
