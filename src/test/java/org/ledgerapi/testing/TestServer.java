package org.ledgerapi.testing;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import org.ledgerapi.LedgerApplication;
import org.ledgerapi.config.ConfigLoader;
import org.ledgerapi.infrastructure.system.Sha256HashProvider;

/** A running application on a disposable database plus a small HTTP client for system tests. */
public final class TestServer implements AutoCloseable {
  public static final String ADMIN_KEY = "test-admin-key-0123456789abcdef";
  public static final String PASSWORD = "correct horse battery";
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final AtomicLong KEYS = new AtomicLong();

  public final FixedClock clock = new FixedClock(Instant.parse("2026-03-01T10:00:00Z"));
  public final Path database;
  private final LedgerApplication app;
  private final HttpClient client = HttpClient.newHttpClient();

  /** A response with its body parsed when it is JSON. */
  public record Reply(int status, String body, JsonNode json, HttpResponse<String> raw) {
    public String code() {
      return json == null ? null : json.at("/error/code").asText(null);
    }

    public String header(String name) {
      return raw.headers().firstValue(name).orElse(null);
    }
  }

  public TestServer(Path dir) {
    this(dir, Map.of());
  }

  public TestServer(Path dir, Map<String, String> overrides) {
    this.database = dir.resolve("ledger.db");
    Map<String, String> env = new HashMap<>();
    String hash = new Sha256HashProvider().sha256Hex(ADMIN_KEY.getBytes(StandardCharsets.UTF_8));
    env.put(ConfigLoader.ADMIN_KEYS, "ops-admin=" + hash);
    env.putAll(overrides);
    app =
        LedgerApplication.start(
            TestConfigs.forDatabase(database, env),
            clock,
            new SequentialIds(),
            null,
            new FastPasswordHasher());
  }

  public int port() {
    return app.port();
  }

  public static String newKey() {
    return String.format("key-%016d", KEYS.incrementAndGet());
  }

  public Reply send(String method, String path, String body, String... headers) {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port() + "/api/v1" + path))
            .method(
                method,
                body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(body));
    boolean hasType = false;
    for (int i = 0; i < headers.length; i += 2) {
      hasType |= headers[i].equalsIgnoreCase("Content-Type");
    }
    if (body != null && !hasType) {
      builder.header("Content-Type", "application/json");
    }
    if (headers.length > 0) {
      builder.headers(headers);
    }
    try {
      HttpResponse<String> response =
          client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
      JsonNode json = null;
      if (!response.body().isEmpty()) {
        try {
          json = JSON.readTree(response.body());
        } catch (Exception ignored) {
          json = null;
        }
      }
      return new Reply(response.statusCode(), response.body(), json, response);
    } catch (Exception e) {
      throw new IllegalStateException(e);
    }
  }

  public Reply admin(String method, String path, String body, String... extra) {
    return send(method, path, body, concat(new String[] {"X-API-Key", ADMIN_KEY}, extra));
  }

  public Reply as(String token, String method, String path, String body, String... extra) {
    return send(
        method, path, body, concat(new String[] {"Authorization", "Bearer " + token}, extra));
  }

  private static String[] concat(String[] a, String[] b) {
    String[] r = new String[a.length + b.length];
    System.arraycopy(a, 0, r, 0, a.length);
    System.arraycopy(b, 0, r, a.length, b.length);
    return r;
  }

  /** A registered, logged-in user with one wallet per requested currency. */
  public record Person(String userId, String token, Map<String, String> wallets) {
    public String wallet(String currency) {
      return wallets.get(currency);
    }
  }

  public Person person(String login, String... currencies) {
    Reply registered =
        send(
            "POST",
            "/users",
            "{\"displayName\":\""
                + login
                + "\",\"loginName\":\""
                + login
                + "\",\"password\":\""
                + PASSWORD
                + "\"}");
    if (registered.status() != 201) {
      throw new IllegalStateException("register failed: " + registered.body());
    }
    String token = login(login).json().get("accessToken").asText();
    String userId = registered.json().get("userId").asText();
    Map<String, String> wallets = new HashMap<>();
    for (String currency : currencies) {
      Reply w =
          as(token, "POST", "/users/" + userId + "/wallets", "{\"currency\":\"" + currency + "\"}");
      if (w.status() != 201) {
        throw new IllegalStateException("wallet failed: " + w.body());
      }
      wallets.put(currency, w.json().get("walletId").asText());
    }
    return new Person(userId, token, wallets);
  }

  public Reply login(String login) {
    return send(
        "POST",
        "/auth/login",
        "{\"loginName\":\"" + login + "\",\"password\":\"" + PASSWORD + "\"}");
  }

  public Reply fund(String walletId, long amount, String currency) {
    return admin(
        "POST",
        "/admin/wallets/" + walletId + "/funding",
        "{\"amount\":" + amount + ",\"currency\":\"" + currency + "\"}",
        "Idempotency-Key",
        newKey());
  }

  public Reply transfer(Person from, String to, long amount, String currency, String key) {
    return as(
        from.token(),
        "POST",
        "/transfers",
        "{\"sourceWalletId\":\""
            + from.wallet(currency)
            + "\",\"destinationWalletId\":\""
            + to
            + "\",\"amount\":"
            + amount
            + ",\"currency\":\""
            + currency
            + "\"}",
        "Idempotency-Key",
        key);
  }

  public long balance(String walletId) {
    return admin("GET", "/wallets/" + walletId, null).json().get("balance").asLong();
  }

  public Reply reconcile() {
    return admin("GET", "/admin/reconciliation", null);
  }

  @Override
  public void close() {
    app.close();
  }
}
