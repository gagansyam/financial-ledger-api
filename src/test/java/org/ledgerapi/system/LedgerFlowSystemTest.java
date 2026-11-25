package org.ledgerapi.system;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import java.nio.file.Path;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ledgerapi.testing.TestServer;
import org.ledgerapi.testing.TestServer.Person;
import org.ledgerapi.testing.TestServer.Reply;

/** End-to-end ledger flows over HTTP against a file-backed database (TQ-3). */
class LedgerFlowSystemTest {
  @TempDir Path dir;
  private TestServer server;

  @BeforeEach
  void start() {
    server = new TestServer(dir);
  }

  @AfterEach
  void stop() {
    server.close();
  }

  @Test
  void registrationReturnsPublicProfileWithoutSecrets() {
    Reply r =
        server.send(
            "POST",
            "/users",
            "{\"displayName\":\"Asha\",\"loginName\":\"asha\",\"password\":\"long enough pw\"}");
    assertThat(r.status()).isEqualTo(201);
    assertThat(r.json().get("displayName").asText()).isEqualTo("Asha");
    assertThat(r.json().get("createdAt").asText()).isEqualTo("2026-03-01T10:00:00.000Z");
    assertThat(r.json().size()).isEqualTo(3);
    assertThat(r.body()).doesNotContain("long enough pw").doesNotContain("password");

    Reply again =
        server.send(
            "POST",
            "/users",
            "{\"displayName\":\"Other\",\"loginName\":\"asha\",\"password\":\"long enough pw\"}");
    assertThat(again.status()).isEqualTo(409);
    assertThat(again.code()).isEqualTo("LOGIN_ALREADY_EXISTS");
  }

  @Test
  void loginLogoutAndExpiry() {
    Person p = server.person("bala", "INR");
    Reply login = server.login("bala");
    assertThat(login.status()).isEqualTo(200);
    assertThat(login.json().get("tokenType").asText()).isEqualTo("Bearer");
    assertThat(login.json().get("expiresAt").asText()).isEqualTo("2026-03-01T11:00:00.000Z");
    assertThat(login.header("Cache-Control")).isEqualTo("no-store");

    String second = login.json().get("accessToken").asText();
    assertThat(server.as(second, "POST", "/auth/logout", null).status()).isEqualTo(204);
    assertThat(server.as(second, "GET", "/users/" + p.userId(), null).code())
        .isEqualTo("UNAUTHENTICATED");
    assertThat(server.as(p.token(), "GET", "/users/" + p.userId(), null).status()).isEqualTo(200);

    server.clock.set(server.clock.now().plus(Duration.ofMinutes(61)));
    assertThat(server.as(p.token(), "GET", "/users/" + p.userId(), null).status()).isEqualTo(401);
  }

  @Test
  void logoutAllRevokesEverySessionOfTheUser() {
    Person p = server.person("chen", "INR");
    String other = server.login("chen").json().get("accessToken").asText();
    assertThat(server.as(p.token(), "POST", "/auth/logout-all", null).status()).isEqualTo(204);
    assertThat(server.as(other, "GET", "/wallets/" + p.wallet("INR"), null).status())
        .isEqualTo(401);
    assertThat(server.as(p.token(), "GET", "/wallets/" + p.wallet("INR"), null).status())
        .isEqualTo(401);
  }

  @Test
  void badCredentialsAreGeneric() {
    server.person("dev", "INR");
    Reply wrong =
        server.send(
            "POST", "/auth/login", "{\"loginName\":\"dev\",\"password\":\"wrong password\"}");
    Reply unknown =
        server.send(
            "POST", "/auth/login", "{\"loginName\":\"nobody\",\"password\":\"wrong password\"}");
    assertThat(wrong.status()).isEqualTo(401);
    assertThat(unknown.status()).isEqualTo(401);
    assertThat(wrong.json().get("error")).isEqualTo(unknown.json().get("error"));
  }

  @Test
  void fundTransferAndReadBack() {
    Person a = server.person("alice", "INR");
    Person b = server.person("bob", "INR");
    Reply funded = server.fund(a.wallet("INR"), 10_000, "INR");
    assertThat(funded.status()).isEqualTo(201);
    assertThat(funded.json().get("type").asText()).isEqualTo("FUNDING");
    assertThat(funded.json().get("sequence").asText()).isEqualTo("1");

    Reply t = server.transfer(a, b.wallet("INR"), 2_500, "INR", TestServer.newKey());
    assertThat(t.status()).isEqualTo(201);
    JsonNode tx = t.json();
    assertThat(tx.get("type").asText()).isEqualTo("TRANSFER");
    assertThat(tx.get("sequence").asText()).isEqualTo("2");
    assertThat(tx.get("amount").isIntegralNumber()).isTrue();
    assertThat(tx.get("entries")).hasSize(2);
    assertThat(tx.get("reversedBy").isNull()).isTrue();
    assertThat(tx.get("hash").asText()).matches("[0-9a-f]{64}");
    assertThat(tx.get("previousHash").asText()).isEqualTo(funded.json().get("hash").asText());

    assertThat(server.balance(a.wallet("INR"))).isEqualTo(7_500);
    assertThat(server.balance(b.wallet("INR"))).isEqualTo(2_500);

    String id = tx.get("transactionId").asText();
    assertThat(server.as(b.token(), "GET", "/transactions/" + id, null).body()).isEqualTo(t.body());
    Person c = server.person("carol", "INR");
    assertThat(server.as(c.token(), "GET", "/transactions/" + id, null).code())
        .isEqualTo("FORBIDDEN");
    assertThat(server.reconcile().json().get("status").asText()).isEqualTo("CONSISTENT");
  }

  @Test
  void replayReturnsIdenticalResponseAndReusedKeyIsRejected() {
    Person a = server.person("erin", "INR");
    Person b = server.person("farid", "INR");
    server.fund(a.wallet("INR"), 1_000, "INR");
    String key = TestServer.newKey();
    Reply first = server.transfer(a, b.wallet("INR"), 400, "INR", key);
    Reply replay = server.transfer(a, b.wallet("INR"), 400, "INR", key);
    assertThat(replay.status()).isEqualTo(201);
    assertThat(replay.body()).isEqualTo(first.body());
    assertThat(replay.header("Idempotency-Replayed")).isEqualTo("true");
    assertThat(first.header("Idempotency-Replayed")).isNull();
    assertThat(server.balance(a.wallet("INR"))).isEqualTo(600);

    Reply reused = server.transfer(a, b.wallet("INR"), 401, "INR", key);
    assertThat(reused.status()).isEqualTo(422);
    assertThat(reused.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

    server.transfer(a, b.wallet("INR"), 600, "INR", TestServer.newKey());
    Reply lateReplay = server.transfer(a, b.wallet("INR"), 400, "INR", key);
    assertThat(lateReplay.status()).isEqualTo(201);
    assertThat(lateReplay.body()).isEqualTo(first.body());
  }

  @Test
  void businessRuleRejectionsLeaveNoTrace() {
    Person a = server.person("gita", "INR", "USD");
    Person b = server.person("hari", "INR", "USD");
    server.fund(a.wallet("INR"), 100, "INR");

    assertThat(server.transfer(a, b.wallet("INR"), 101, "INR", TestServer.newKey()).code())
        .isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(server.transfer(a, a.wallet("INR"), 10, "INR", TestServer.newKey()).code())
        .isEqualTo("SAME_WALLET_TRANSFER");
    assertThat(server.transfer(a, b.wallet("USD"), 10, "INR", TestServer.newKey()).code())
        .isEqualTo("CURRENCY_MISMATCH");
    assertThat(
            server
                .transfer(a, "00000000-0000-4000-8000-0000000fffff", 10, "INR", TestServer.newKey())
                .code())
        .isEqualTo("NOT_FOUND");
    assertThat(server.balance(a.wallet("INR"))).isEqualTo(100);
    JsonNode report = server.reconcile().json();
    assertThat(report.get("transactionCount").asText()).isEqualTo("1");
    assertThat(report.get("status").asText()).isEqualTo("CONSISTENT");
  }

  @Test
  void foreignSourceIsForbiddenAndAdminCannotTransfer() {
    Person a = server.person("ivan", "INR");
    Person b = server.person("jaya", "INR");
    server.fund(a.wallet("INR"), 100, "INR");
    Reply stolen =
        server.as(
            b.token(),
            "POST",
            "/transfers",
            "{\"sourceWalletId\":\""
                + a.wallet("INR")
                + "\",\"destinationWalletId\":\""
                + b.wallet("INR")
                + "\",\"amount\":5,\"currency\":\"INR\"}",
            "Idempotency-Key",
            TestServer.newKey());
    assertThat(stolen.code()).isEqualTo("FORBIDDEN");
    Reply admin =
        server.admin(
            "POST",
            "/transfers",
            "{\"sourceWalletId\":\""
                + a.wallet("INR")
                + "\",\"destinationWalletId\":\""
                + b.wallet("INR")
                + "\",\"amount\":5,\"currency\":\"INR\"}",
            "Idempotency-Key",
            TestServer.newKey());
    assertThat(admin.code()).isEqualTo("FORBIDDEN");
    assertThat(server.as(a.token(), "GET", "/admin/reconciliation", null).code())
        .isEqualTo("FORBIDDEN");
    assertThat(server.as(b.token(), "GET", "/wallets/" + a.wallet("INR"), null).code())
        .isEqualTo("FORBIDDEN");
    assertThat(server.as(b.token(), "GET", "/users/" + a.userId(), null).code())
        .isEqualTo("FORBIDDEN");
    assertThat(
            server
                .as(
                    b.token(),
                    "POST",
                    "/users/" + a.userId() + "/wallets",
                    "{\"currency\":\"USD\"}")
                .code())
        .isEqualTo("FORBIDDEN");
    assertThat(server.admin("POST", "/auth/logout", null).code()).isEqualTo("FORBIDDEN");
  }

  @Test
  void walletRules() {
    Person a = server.person("kiran", "INR");
    Reply dup =
        server.as(a.token(), "POST", "/users/" + a.userId() + "/wallets", "{\"currency\":\"INR\"}");
    assertThat(dup.code()).isEqualTo("WALLET_ALREADY_EXISTS");
    Reply unsupported =
        server.as(a.token(), "POST", "/users/" + a.userId() + "/wallets", "{\"currency\":\"JPY\"}");
    assertThat(unsupported.code()).isEqualTo("UNSUPPORTED_CURRENCY");
    Reply lower =
        server.as(a.token(), "POST", "/users/" + a.userId() + "/wallets", "{\"currency\":\"usd\"}");
    assertThat(lower.code()).isEqualTo("VALIDATION_ERROR");
    Reply byAdmin =
        server.admin("POST", "/users/" + a.userId() + "/wallets", "{\"currency\":\"EUR\"}");
    assertThat(byAdmin.status()).isEqualTo(201);
    assertThat(byAdmin.json().get("balance").asLong()).isZero();
    Reply profile = server.admin("GET", "/users/" + a.userId(), null);
    assertThat(profile.json().get("wallets")).hasSize(2);
    assertThat(server.admin("GET", "/users/00000000-0000-4000-8000-00000000abcd", null).code())
        .isEqualTo("NOT_FOUND");
  }

  @Test
  void fundingRules() {
    Person a = server.person("lata", "INR");
    assertThat(server.fund(a.wallet("INR"), 10, "USD").code()).isEqualTo("CURRENCY_MISMATCH");
    assertThat(server.fund(a.wallet("INR"), 10, "JPY").code()).isEqualTo("UNSUPPORTED_CURRENCY");
    assertThat(server.fund("00000000-0000-4000-8000-00000000abcd", 10, "INR").code())
        .isEqualTo("NOT_FOUND");
    assertThat(server.fund(a.wallet("INR"), 9_007_199_254_740_991L, "INR").code())
        .isEqualTo("INVALID_AMOUNT");
    for (int i = 0; i < 9; i++) {
      assertThat(server.fund(a.wallet("INR"), 1_000_000_000_000L, "INR").status()).isEqualTo(201);
    }
    assertThat(server.balance(a.wallet("INR"))).isEqualTo(9_000_000_000_000L);
    assertThat(server.reconcile().json().at("/totals/0/totalCredits").asText())
        .isEqualTo("9000000000000");
  }

  @Test
  void reversalMirrorsOriginalOnceAndOnlyOnce() {
    Person a = server.person("mona", "INR");
    Person b = server.person("nils", "INR");
    server.fund(a.wallet("INR"), 500, "INR");
    Reply t = server.transfer(a, b.wallet("INR"), 200, "INR", TestServer.newKey());
    String id = t.json().get("transactionId").asText();
    String key = TestServer.newKey();
    Reply rev =
        server.admin(
            "POST",
            "/admin/transactions/" + id + "/reversal",
            "{\"reason\":\"customer dispute\"}",
            "Idempotency-Key",
            key);
    assertThat(rev.status()).isEqualTo(201);
    assertThat(rev.json().get("type").asText()).isEqualTo("REVERSAL");
    assertThat(rev.json().get("reversalOf").asText()).isEqualTo(id);
    assertThat(rev.json().get("reason").asText()).isEqualTo("customer dispute");
    assertThat(server.balance(a.wallet("INR"))).isEqualTo(500);
    assertThat(server.balance(b.wallet("INR"))).isZero();

    Reply replay =
        server.admin(
            "POST",
            "/admin/transactions/" + id + "/reversal",
            "{\"reason\":\"customer dispute\"}",
            "Idempotency-Key",
            key);
    assertThat(replay.body()).isEqualTo(rev.body());
    Reply again =
        server.admin(
            "POST",
            "/admin/transactions/" + id + "/reversal",
            null,
            "Idempotency-Key",
            TestServer.newKey());
    assertThat(again.code()).isEqualTo("ALREADY_REVERSED");
    String revId = rev.json().get("transactionId").asText();
    Reply ofReversal =
        server.admin(
            "POST",
            "/admin/transactions/" + revId + "/reversal",
            null,
            "Idempotency-Key",
            TestServer.newKey());
    assertThat(ofReversal.code()).isEqualTo("REVERSAL_NOT_ALLOWED");
    assertThat(server.admin("GET", "/transactions/" + id, null).json().get("reversedBy").asText())
        .isEqualTo(revId);
    assertThat(server.reconcile().json().get("status").asText()).isEqualTo("CONSISTENT");
  }

  @Test
  void reversalNeedingFundsTheDestinationNoLongerHasIsRejected() {
    Person a = server.person("omar", "INR");
    Person b = server.person("pia", "INR");
    Person c = server.person("quinn", "INR");
    server.fund(a.wallet("INR"), 300, "INR");
    String id =
        server
            .transfer(a, b.wallet("INR"), 300, "INR", TestServer.newKey())
            .json()
            .get("transactionId")
            .asText();
    server.transfer(b, c.wallet("INR"), 300, "INR", TestServer.newKey());
    Reply rev =
        server.admin(
            "POST",
            "/admin/transactions/" + id + "/reversal",
            null,
            "Idempotency-Key",
            TestServer.newKey());
    assertThat(rev.code()).isEqualTo("INSUFFICIENT_FUNDS");
  }

  @Test
  void entriesArePagedNewestFirst() {
    Person a = server.person("ravi", "INR");
    Person b = server.person("sara", "INR");
    server.fund(a.wallet("INR"), 1_000, "INR");
    for (int i = 1; i <= 4; i++) {
      server.transfer(a, b.wallet("INR"), i, "INR", TestServer.newKey());
    }
    String path = "/wallets/" + a.wallet("INR") + "/entries";
    Reply first = server.as(a.token(), "GET", path + "?limit=2", null);
    assertThat(first.status()).isEqualTo(200);
    JsonNode items = first.json().get("items");
    assertThat(items).hasSize(2);
    assertThat(items.get(0).get("amount").asLong()).isEqualTo(4);
    assertThat(items.get(0).get("direction").asText()).isEqualTo("DEBIT");
    String next = first.json().get("nextBefore").asText();
    assertThat(next).isEqualTo(items.get(1).get("entryNumber").asText());
    Reply second = server.as(a.token(), "GET", path + "?limit=2&before=" + next, null);
    Reply third =
        server.as(
            a.token(),
            "GET",
            path + "?limit=2&before=" + second.json().get("nextBefore").asText(),
            null);
    assertThat(third.json().get("items")).hasSize(1);
    assertThat(third.json().get("items").get(0).get("direction").asText()).isEqualTo("CREDIT");
    assertThat(third.json().get("nextBefore").isNull()).isTrue();

    for (String bad :
        new String[] {
          "?limit=0",
          "?limit=101",
          "?limit=abc",
          "?before=0",
          "?before=-1",
          "?before=99999999999999999999",
          "?limit=1&limit=2",
          "?page=1",
          "?limit=01"
        }) {
      assertThat(server.as(a.token(), "GET", path + bad, null).code())
          .as(bad)
          .isEqualTo("VALIDATION_ERROR");
    }
    assertThat(server.as(a.token(), "GET", "/wallets/" + a.wallet("INR") + "?x=1", null).code())
        .isEqualTo("VALIDATION_ERROR");
  }
}
