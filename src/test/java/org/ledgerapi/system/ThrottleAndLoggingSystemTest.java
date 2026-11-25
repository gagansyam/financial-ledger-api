package org.ledgerapi.system;

import static org.assertj.core.api.Assertions.assertThat;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ledgerapi.config.ConfigLoader;
import org.ledgerapi.testing.TestServer;
import org.ledgerapi.testing.TestServer.Person;
import org.ledgerapi.testing.TestServer.Reply;
import org.slf4j.LoggerFactory;
import org.slf4j.event.KeyValuePair;

/** Authentication throttling (NFR-SEC-9) and structured logs (NFR-OBS-2, NFR-SEC-5). */
class ThrottleAndLoggingSystemTest {
  @TempDir Path dir;
  private TestServer server;

  @AfterEach
  void stop() {
    if (server != null) {
      server.close();
    }
  }

  private Reply badLogin(String login, String... headers) {
    return server.send(
        "POST",
        "/auth/login",
        "{\"loginName\":\"" + login + "\",\"password\":\"not the password\"}",
        headers);
  }

  @Test
  void fifthFailureLocksTheLoginNameUntilTheWindowPasses() {
    server = new TestServer(dir);
    server.person("tara", "INR");
    for (int i = 0; i < 5; i++) {
      assertThat(badLogin("tara").status()).isEqualTo(401);
    }
    Reply locked = server.login("tara");
    assertThat(locked.status()).isEqualTo(429);
    assertThat(locked.code()).isEqualTo("TOO_MANY_REQUESTS");
    assertThat(Long.parseLong(locked.header("Retry-After"))).isBetween(1L, 900L);
    server.clock.set(server.clock.now().plus(Duration.ofMinutes(15)));
    assertThat(server.login("tara").status()).isEqualTo(200);
  }

  @Test
  void registrationIsLimitedPerSourceAddress() {
    server = new TestServer(dir);
    for (int i = 0; i < 10; i++) {
      server.send(
          "POST",
          "/users",
          "{\"displayName\":\"x\",\"loginName\":\"u" + i + "x\",\"password\":\"long enough pw\"}");
    }
    Reply r =
        server.send(
            "POST",
            "/users",
            "{\"displayName\":\"x\",\"loginName\":\"late\",\"password\":\"long enough pw\"}");
    assertThat(r.status()).isEqualTo(429);
    assertThat(r.header("Retry-After")).isNotNull();
  }

  @Test
  void forwardedForIsIgnoredUnlessPeerIsTrusted() {
    server = new TestServer(dir);
    for (int i = 0; i < 20; i++) {
      badLogin("n" + i + "x", "X-Forwarded-For", "10.0.0." + i);
    }
    assertThat(badLogin("other", "X-Forwarded-For", "10.9.9.9").status()).isEqualTo(429);
  }

  @Test
  void trustedProxyForwardsTheClientAddress() {
    server = new TestServer(dir, Map.of(ConfigLoader.TRUSTED_PROXIES, "127.0.0.1"));
    for (int i = 0; i < 20; i++) {
      badLogin("n" + i + "x", "X-Forwarded-For", "10.0.0.1");
    }
    assertThat(badLogin("other", "X-Forwarded-For", "10.0.0.1").status()).isEqualTo(429);
    assertThat(badLogin("other", "X-Forwarded-For", "10.0.0.2").status()).isEqualTo(401);
  }

  @Test
  void transactionAndReplayLogsCarryNoSecrets() throws Exception {
    Logger tx = (Logger) LoggerFactory.getLogger("ledger.transaction");
    Logger req = (Logger) LoggerFactory.getLogger("ledger.request");
    ListAppender<ILoggingEvent> txLog = new ListAppender<>();
    ListAppender<ILoggingEvent> reqLog = new ListAppender<>();
    txLog.start();
    reqLog.start();
    tx.addAppender(txLog);
    req.addAppender(reqLog);
    try {
      server = new TestServer(dir);
      Person a = server.person("uma", "INR");
      Person b = server.person("vik", "INR");
      server.fund(a.wallet("INR"), 50, "INR");
      String key = TestServer.newKey();
      server.transfer(a, b.wallet("INR"), 5, "INR", key);
      server.transfer(a, b.wallet("INR"), 5, "INR", key);
      long deadline = System.nanoTime() + 5_000_000_000L;
      while (replays(reqLog) < 1 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
    } finally {
      tx.detachAppender(txLog);
      req.detachAppender(reqLog);
    }
    assertThat(txLog.list).hasSize(2);
    Map<String, Object> committed = fields(txLog.list.get(1));
    assertThat(committed)
        .containsKeys("transactionId", "sequence")
        .containsEntry("type", "TRANSFER");
    assertThat(replays(reqLog)).isEqualTo(1);
    List<String> all = new ArrayList<>();
    for (ILoggingEvent e : reqLog.list) {
      all.add(fields(e).toString() + e.getFormattedMessage());
    }
    assertThat(String.join("\n", all))
        .doesNotContain(TestServer.ADMIN_KEY)
        .doesNotContain(TestServer.PASSWORD)
        .doesNotContain("Bearer");
  }

  private static long replays(ListAppender<ILoggingEvent> log) {
    return List.copyOf(log.list).stream()
        .filter(e -> Boolean.TRUE.equals(fields(e).get("idempotencyReplay")))
        .count();
  }

  private static Map<String, Object> fields(ILoggingEvent event) {
    Map<String, Object> result = new HashMap<>();
    List<KeyValuePair> pairs = event.getKeyValuePairs();
    if (pairs != null) {
      for (KeyValuePair pair : pairs) {
        result.put(pair.key, pair.value);
      }
    }
    return result;
  }
}
