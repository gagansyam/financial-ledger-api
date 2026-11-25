package org.ledgerapi.http;

import io.javalin.http.Context;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.ledgerapi.application.AuthService.LoginResult;
import org.ledgerapi.application.LedgerServices;
import org.ledgerapi.application.MoneyResult;
import org.ledgerapi.application.TransferService.TransferRequest;
import org.ledgerapi.application.UserService.UserProfile;
import org.ledgerapi.application.WalletService;
import org.ledgerapi.application.WalletService.EntryPage;
import org.ledgerapi.domain.CanonicalJson;
import org.ledgerapi.domain.Entry;
import org.ledgerapi.domain.ErrorCode;
import org.ledgerapi.domain.FieldError;
import org.ledgerapi.domain.LedgerException;
import org.ledgerapi.domain.LedgerTransaction;
import org.ledgerapi.domain.Timestamps;
import org.ledgerapi.domain.User;
import org.ledgerapi.domain.Wallet;
import org.ledgerapi.http.BodySchema.Kind;
import org.ledgerapi.http.Endpoint.Access;
import org.ledgerapi.http.Endpoint.Call;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Handlers of the FR-1 to FR-7 routes. Representations follow docs/api/openapi.yaml. */
final class LedgerRoutes {
  static final String TRANSACTION_LOGGER = "ledger.transaction";
  static final String REPLAY_ATTRIBUTE = "ledger.idempotencyReplay";
  private static final Logger TRANSACTION_LOG = LoggerFactory.getLogger(TRANSACTION_LOGGER);
  private static final Pattern LOGIN_NAME = Pattern.compile("[a-z0-9_-]{3,64}");
  private static final Pattern LIMIT = Pattern.compile("[1-9][0-9]{0,2}");
  private static final Pattern CURSOR = Pattern.compile("[1-9][0-9]{0,18}");
  private static final int SHORT_TEXT = 140;

  private final LedgerServices services;
  private final ClientAddress clientAddress;

  LedgerRoutes(LedgerServices services, ClientAddress clientAddress) {
    this.services = services;
    this.clientAddress = clientAddress;
  }

  /** A route to register: method, path relative to the base path, and its pipeline. */
  record Route(String method, String path, Endpoint endpoint) {}

  List<Route> routes() {
    var auth = services.auth();
    List<Route> routes = new ArrayList<>();
    routes.add(
        new Route(
            "POST",
            "/users",
            Endpoint.builder(auth, Access.PUBLIC, this::register)
                .body(
                    BodySchema.of()
                        .field("displayName", Kind.STRING)
                        .field("loginName", Kind.STRING)
                        .field("password", Kind.STRING))
                .build()));
    routes.add(
        new Route(
            "POST",
            "/auth/login",
            Endpoint.builder(auth, Access.PUBLIC, this::login)
                .body(
                    BodySchema.of().field("loginName", Kind.STRING).field("password", Kind.STRING))
                .build()));
    routes.add(
        new Route(
            "POST", "/auth/logout", Endpoint.builder(auth, Access.USER, this::logout).build()));
    routes.add(
        new Route(
            "POST",
            "/auth/logout-all",
            Endpoint.builder(auth, Access.USER, this::logoutAll).build()));
    routes.add(
        new Route(
            "GET",
            "/users/{userId}",
            Endpoint.builder(auth, Access.ANY, this::getUser).pathId("userId").build()));
    routes.add(
        new Route(
            "POST",
            "/users/{userId}/wallets",
            Endpoint.builder(auth, Access.ANY, this::createWallet)
                .pathId("userId")
                .body(BodySchema.of().field("currency", Kind.STRING))
                .build()));
    routes.add(
        new Route(
            "GET",
            "/wallets/{walletId}",
            Endpoint.builder(auth, Access.ANY, this::getWallet).pathId("walletId").build()));
    routes.add(
        new Route(
            "GET",
            "/wallets/{walletId}/entries",
            Endpoint.builder(auth, Access.ANY, this::entries)
                .pathId("walletId")
                .query("limit", "before")
                .build()));
    routes.add(
        new Route(
            "POST",
            "/transfers",
            Endpoint.builder(auth, Access.USER, this::transfer)
                .idempotent()
                .body(
                    BodySchema.of()
                        .field("sourceWalletId", Kind.STRING)
                        .field("destinationWalletId", Kind.STRING)
                        .field("amount", Kind.AMOUNT)
                        .field("currency", Kind.STRING)
                        .field("reference", Kind.OPTIONAL_STRING))
                .build()));
    routes.add(
        new Route(
            "GET",
            "/transactions/{transactionId}",
            Endpoint.builder(auth, Access.ANY, this::getTransaction)
                .pathId("transactionId")
                .build()));
    routes.add(
        new Route(
            "POST",
            "/admin/wallets/{walletId}/funding",
            Endpoint.builder(auth, Access.ADMIN, this::fund)
                .pathId("walletId")
                .idempotent()
                .body(BodySchema.of().field("amount", Kind.AMOUNT).field("currency", Kind.STRING))
                .build()));
    routes.add(
        new Route(
            "POST",
            "/admin/transactions/{transactionId}/reversal",
            Endpoint.builder(auth, Access.ADMIN, this::reverse)
                .pathId("transactionId")
                .idempotent()
                .optionalBody(BodySchema.of().field("reason", Kind.OPTIONAL_STRING))
                .build()));
    routes.add(
        new Route(
            "GET",
            "/admin/reconciliation",
            Endpoint.builder(auth, Access.ADMIN, this::reconcile).build()));
    return routes;
  }

  private void register(Call call) {
    Context ctx = call.ctx();
    services.throttle().acquireRegistration(clientIp(ctx));
    String displayName = call.body().text("displayName", 1, 100);
    String loginName = call.body().matching("loginName", LOGIN_NAME);
    String password = call.body().text("password", 12, 128);
    User user = services.users().register(displayName, loginName, password);
    json(ctx, 201, user(user));
  }

  private void login(Call call) {
    Context ctx = call.ctx();
    String loginName = call.body().string("loginName");
    String password = call.body().string("password");
    LoginResult result = services.auth().login(loginName, password, clientIp(ctx));
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("accessToken", result.accessToken());
    body.put("tokenType", "Bearer");
    body.put("expiresAt", Timestamps.format(result.expiresAt()));
    body.put("userId", result.userId());
    ctx.header("Cache-Control", "no-store");
    json(ctx, 200, body);
  }

  private void logout(Call call) {
    services.auth().logout(call.principal());
    call.ctx().status(204);
  }

  private void logoutAll(Call call) {
    services.auth().logoutAll(call.principal());
    call.ctx().status(204);
  }

  private void getUser(Call call) {
    UserProfile profile = services.users().get(call.principal(), call.id("userId"));
    Map<String, Object> body = user(profile.user());
    List<Object> wallets = new ArrayList<>();
    for (Wallet w : profile.wallets()) {
      wallets.add(wallet(w));
    }
    body.put("wallets", wallets);
    json(call.ctx(), 200, body);
  }

  private void createWallet(Call call) {
    String currency = call.body().currency("currency");
    Wallet created = services.wallets().create(call.principal(), call.id("userId"), currency);
    json(call.ctx(), 201, wallet(created));
  }

  private void getWallet(Call call) {
    json(call.ctx(), 200, wallet(services.wallets().get(call.principal(), call.id("walletId"))));
  }

  private void entries(Call call) {
    int limit = WalletService.DEFAULT_PAGE_SIZE;
    String rawLimit = call.query().get("limit");
    if (rawLimit != null) {
      if (!LIMIT.matcher(rawLimit).matches()
          || Integer.parseInt(rawLimit) > WalletService.MAX_PAGE_SIZE) {
        throw queryError("limit");
      }
      limit = Integer.parseInt(rawLimit);
    }
    Long before = null;
    String rawBefore = call.query().get("before");
    if (rawBefore != null) {
      if (!CURSOR.matcher(rawBefore).matches()) {
        throw queryError("before");
      }
      try {
        before = Long.parseLong(rawBefore);
      } catch (NumberFormatException e) {
        throw queryError("before");
      }
    }
    EntryPage page =
        services.wallets().entries(call.principal(), call.id("walletId"), before, limit);
    List<Object> items = new ArrayList<>();
    for (Entry e : page.items()) {
      Map<String, Object> item = new LinkedHashMap<>();
      item.put("entryNumber", Long.toString(e.entryNumber()));
      item.put("transactionId", e.transactionId());
      item.put("direction", e.direction().name());
      item.put("amount", e.amount());
      item.put("currency", e.currency());
      item.put("createdAt", e.createdAt());
      items.add(item);
    }
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", items);
    body.put("nextBefore", page.nextBefore() == null ? null : page.nextBefore().toString());
    json(call.ctx(), 200, body);
  }

  private void transfer(Call call) {
    BodySchema.Validated b = call.body();
    String source = b.uuid("sourceWalletId");
    String destination = b.uuid("destinationWalletId");
    long amount = b.amount("amount");
    String currency = b.currency("currency");
    String reference = b.optionalText("reference", SHORT_TEXT);
    MoneyResult result =
        services
            .transfers()
            .transfer(
                call.principal(),
                call.idempotencyKey(),
                new TransferRequest(source, destination, amount, currency, reference));
    money(call.ctx(), result);
  }

  private void getTransaction(Call call) {
    String body = services.transactions().get(call.principal(), call.id("transactionId"));
    call.ctx().status(200).contentType("application/json").result(body);
  }

  private void fund(Call call) {
    long amount = call.body().amount("amount");
    String currency = call.body().currency("currency");
    MoneyResult result =
        services
            .funding()
            .fund(call.principal(), call.idempotencyKey(), call.id("walletId"), amount, currency);
    money(call.ctx(), result);
  }

  private void reverse(Call call) {
    String reason = call.body().optionalText("reason", SHORT_TEXT);
    MoneyResult result =
        services
            .reversals()
            .reverse(call.principal(), call.idempotencyKey(), call.id("transactionId"), reason);
    money(call.ctx(), result);
  }

  private void reconcile(Call call) {
    json(call.ctx(), 200, services.reconciliation().reconcile().toBody());
  }

  private void money(Context ctx, MoneyResult result) {
    if (result.replayed()) {
      ctx.header("Idempotency-Replayed", "true");
      ctx.attribute(REPLAY_ATTRIBUTE, Boolean.TRUE);
    }
    LedgerTransaction committed = result.committed();
    if (committed != null) {
      TRANSACTION_LOG
          .atInfo()
          .addKeyValue("transactionId", committed.transactionId())
          .addKeyValue("sequence", committed.sequence())
          .addKeyValue("type", committed.type().name())
          .log("committed");
    }
    ctx.status(result.status()).contentType("application/json").result(result.body());
  }

  private String clientIp(Context ctx) {
    return clientAddress.resolve(
        ctx.req().getRemoteAddr(), ctx.req().getHeaders("X-Forwarded-For"));
  }

  private static LedgerException queryError(String name) {
    return new LedgerException(
        ErrorCode.VALIDATION_ERROR, "Invalid request", List.of(new FieldError(name, "format")));
  }

  private static Map<String, Object> user(User user) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("userId", user.userId());
    body.put("displayName", user.displayName());
    body.put("createdAt", user.createdAt());
    return body;
  }

  private static Map<String, Object> wallet(Wallet wallet) {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("walletId", wallet.walletId());
    body.put("userId", wallet.userId());
    body.put("currency", wallet.currency());
    body.put("balance", wallet.balance());
    body.put("createdAt", wallet.createdAt());
    return body;
  }

  private static void json(Context ctx, int status, Map<String, Object> body) {
    ctx.status(status).contentType("application/json").result(CanonicalJson.write(body));
  }
}
