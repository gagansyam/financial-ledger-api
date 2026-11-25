package org.ledgerapi.system;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.ledgerapi.testing.TestServer;
import org.ledgerapi.testing.TestServer.Person;
import org.ledgerapi.testing.TestServer.Reply;

/** Request validation and its SRS 4.4 order (NFR-API-1, NFR-API-7, NFR-SEC-1, NFR-SEC-2). */
class RequestValidationSystemTest {
  @TempDir Path dir;
  private TestServer server;
  private Person alice;
  private Person bob;

  @BeforeEach
  void start() {
    server = new TestServer(dir);
    alice = server.person("alice", "INR");
    bob = server.person("bob", "INR");
    server.fund(alice.wallet("INR"), 1_000, "INR");
  }

  @AfterEach
  void stop() {
    server.close();
  }

  private Reply transferBody(String body, String... headers) {
    String[] all = new String[headers.length + 2];
    all[0] = "Idempotency-Key";
    all[1] = TestServer.newKey();
    System.arraycopy(headers, 0, all, 2, headers.length);
    return server.as(alice.token(), "POST", "/transfers", body, all);
  }

  private String transfer(String amountToken) {
    return "{\"sourceWalletId\":\""
        + alice.wallet("INR")
        + "\",\"destinationWalletId\":\""
        + bob.wallet("INR")
        + "\",\"amount\":"
        + amountToken
        + ",\"currency\":\"INR\"}";
  }

  @Test
  void amountMustBeAnIntegerTokenInRange() {
    for (String token :
        new String[] {
          "0",
          "-1",
          "1.0",
          "1e2",
          "\"5\"",
          "1000000000001",
          "null",
          "true",
          "99999999999999999999",
          "{}",
          "[]"
        }) {
      Reply r = transferBody(transfer(token));
      assertThat(r.status()).as(token).isEqualTo(400);
      assertThat(r.code()).as(token).isEqualTo("INVALID_AMOUNT");
    }
    assertThat(transferBody(transfer("1000000000000")).code()).isEqualTo("INSUFFICIENT_FUNDS");
    assertThat(server.balance(alice.wallet("INR"))).isEqualTo(1_000);
  }

  @Test
  void structuralErrorsAreValidationErrorsWithDetails() {
    Reply missing =
        transferBody(
            "{\"sourceWalletId\":\""
                + alice.wallet("INR")
                + "\",\"destinationWalletId\":\""
                + bob.wallet("INR")
                + "\",\"currency\":\"INR\"}");
    assertThat(missing.code()).isEqualTo("VALIDATION_ERROR");
    assertThat(missing.json().at("/error/details/0/field").asText()).isEqualTo("amount");
    assertThat(missing.json().at("/error/details/0/rule").asText()).isEqualTo("required");

    Reply unknown = transferBody(transfer("5").replace("}", ",\"memo\":\"x\"}"));
    assertThat(unknown.json().at("/error/details/0/rule").asText()).isEqualTo("unknown");

    Reply duplicate = transferBody(transfer("5").replace("}", ",\"amount\":6}"));
    assertThat(duplicate.json().at("/error/details/0/rule").asText()).isEqualTo("duplicate");

    assertThat(transferBody("{not json").code()).isEqualTo("VALIDATION_ERROR");
    assertThat(transferBody("[1,2]").code()).isEqualTo("VALIDATION_ERROR");
    assertThat(transferBody(transfer("5") + "{}").code()).isEqualTo("VALIDATION_ERROR");
    assertThat(transferBody(transfer("5").replace("\"INR\"", "5")).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(transferBody(transfer("5").replace("}", ",\"reference\":\"a\\u0007b\"}")).code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(
            transferBody(transfer("5").replace("}", ",\"reference\":\"" + "x".repeat(141) + "\"}"))
                .code())
        .isEqualTo("VALIDATION_ERROR");
    assertThat(
            transferBody(transfer("5").replace("}", ",\"reference\":\"" + "é".repeat(140) + "\"}"))
                .status())
        .isEqualTo(201);
  }

  @Test
  void identifiersAndIdempotencyHeaders() {
    String body = transfer("5").replace(bob.wallet("INR"), "NOT-A-UUID");
    assertThat(transferBody(body).code()).isEqualTo("MALFORMED_ID");
    assertThat(server.admin("GET", "/wallets/ABC", null).code()).isEqualTo("MALFORMED_ID");
    assertThat(server.admin("GET", "/wallets/00000000-0000-1000-8000-000000000001", null).code())
        .isEqualTo("MALFORMED_ID");

    Reply noKey = server.as(alice.token(), "POST", "/transfers", transfer("5"));
    assertThat(noKey.code()).isEqualTo("MISSING_IDEMPOTENCY_KEY");
    Reply shortKey =
        server.as(alice.token(), "POST", "/transfers", transfer("5"), "Idempotency-Key", "short");
    assertThat(shortKey.code()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
    Reply twoKeys =
        server.as(
            alice.token(),
            "POST",
            "/transfers",
            transfer("5"),
            "Idempotency-Key",
            TestServer.newKey(),
            "Idempotency-Key",
            TestServer.newKey());
    assertThat(twoKeys.code()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
  }

  @Test
  void mediaTypeThenSizeThenAuthentication() {
    String big = "{\"reference\":\"" + "x".repeat(17_000) + "\"}";
    assertThat(server.send("POST", "/transfers", big, "Content-Type", "text/plain").code())
        .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    assertThat(server.send("POST", "/transfers", big).code()).isEqualTo("PAYLOAD_TOO_LARGE");
    assertThat(server.send("POST", "/transfers", transfer("5")).code())
        .isEqualTo("UNAUTHENTICATED");
    assertThat(
            server
                .send(
                    "POST",
                    "/transfers",
                    transfer("5"),
                    "Content-Type",
                    "application/json; charset=latin-1")
                .code())
        .isEqualTo("UNSUPPORTED_MEDIA_TYPE");
    assertThat(
            transferBody(transfer("5"), "Content-Type", "application/json; charset=UTF-8").status())
        .isEqualTo(201);
  }

  @Test
  void charsetUtf8IsAccepted() {
    Reply r =
        server.as(
            alice.token(),
            "POST",
            "/transfers",
            transfer("5"),
            "Idempotency-Key",
            TestServer.newKey(),
            "Content-Type",
            "application/json; charset=utf-8");
    assertThat(r.status()).isEqualTo(201);
  }

  @Test
  void authenticationHeadersMustBeUnambiguous() {
    String path = "/wallets/" + alice.wallet("INR");
    assertThat(server.send("GET", path, null).code()).isEqualTo("UNAUTHENTICATED");
    assertThat(server.send("GET", path, null, "Authorization", "Basic abc").code())
        .isEqualTo("UNAUTHENTICATED");
    assertThat(server.send("GET", path, null, "X-API-Key", "wrong").code())
        .isEqualTo("UNAUTHENTICATED");
    assertThat(
            server
                .send(
                    "GET",
                    path,
                    null,
                    "Authorization",
                    "Bearer " + alice.token(),
                    "X-API-Key",
                    TestServer.ADMIN_KEY)
                .code())
        .isEqualTo("UNAUTHENTICATED");
    assertThat(server.as(alice.token(), "GET", path, null).status()).isEqualTo(200);
  }

  @Test
  void roleIsCheckedBeforeBody() {
    Reply r = server.as(alice.token(), "POST", "/admin/wallets/x/funding", "{bad");
    assertThat(r.code()).isEqualTo("FORBIDDEN");
  }

  @Test
  void errorsNeverLeakInternals() {
    Reply r = transferBody("{\"amount\":");
    assertThat(r.body())
        .doesNotContain("Exception")
        .doesNotContain("jackson")
        .doesNotContain(dir.toString());
  }

  @Test
  void registrationFieldRules() {
    assertThat(
            server
                .send(
                    "POST",
                    "/users",
                    "{\"displayName\":\"A\",\"loginName\":\"UPPER\",\"password\":\"long enough pw\"}")
                .json()
                .at("/error/details/0/field")
                .asText())
        .isEqualTo("loginName");
    assertThat(
            server
                .send(
                    "POST",
                    "/users",
                    "{\"displayName\":\"A\",\"loginName\":\"abc\",\"password\":\"short\"}")
                .json()
                .at("/error/details/0/field")
                .asText())
        .isEqualTo("password");
    assertThat(
            server
                .send(
                    "POST",
                    "/users",
                    "{\"displayName\":\"\",\"loginName\":\"abc\",\"password\":\"short\"}")
                .json()
                .at("/error/details/0/field")
                .asText())
        .isEqualTo("displayName");
  }
}
