package org.ledgerapi.http;

import io.javalin.http.Context;
import io.javalin.http.Handler;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.ledgerapi.application.AuthService;
import org.ledgerapi.domain.ErrorCode;
import org.ledgerapi.domain.FieldError;
import org.ledgerapi.domain.Ids;
import org.ledgerapi.domain.LedgerException;
import org.ledgerapi.domain.Principal;

/**
 * One route's request pipeline in the SRS 4.4 order: media type, body size, authentication, role,
 * JSON structure, path identifiers, idempotency header, then the handler validates fields in schema
 * order, then query parameters.
 */
final class Endpoint implements Handler {
  static final int MAX_BODY_BYTES = 16_384;
  static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
  private static final Pattern IDEMPOTENCY_KEY = Pattern.compile("[A-Za-z0-9_-]{16,64}");
  private static final Pattern MEDIA_TYPE =
      Pattern.compile("application/json(\\s*;\\s*charset\\s*=\\s*\"?utf-8\"?)?\\s*");

  /** Which credential a route accepts. */
  enum Access {
    PUBLIC,
    /** A USER session or an ADMIN key; ownership is decided by the service. */
    ANY,
    /** A USER session; an authenticated administrator receives 403. */
    USER,
    /** An ADMIN key; an authenticated user receives 403. */
    ADMIN
  }

  /** The route logic, given a request that passed the generic pipeline steps. */
  @FunctionalInterface
  interface Action {
    void handle(Call call) throws Exception;
  }

  private final AuthService auth;
  private final Access access;
  private final BodySchema schema;
  private final boolean bodyOptional;
  private final List<String> pathIds;
  private final boolean idempotent;
  private final Set<String> queryParameters;
  private final Action action;

  private Endpoint(Builder b) {
    this.auth = b.auth;
    this.access = b.access;
    this.schema = b.schema;
    this.bodyOptional = b.bodyOptional;
    this.pathIds = List.copyOf(b.pathIds);
    this.idempotent = b.idempotent;
    this.queryParameters = Set.copyOf(b.queryParameters);
    this.action = b.action;
  }

  static Builder builder(AuthService auth, Access access, Action action) {
    return new Builder(auth, access, action);
  }

  static final class Builder {
    private final AuthService auth;
    private final Access access;
    private final Action action;
    private BodySchema schema;
    private boolean bodyOptional;
    private final List<String> pathIds = new ArrayList<>();
    private boolean idempotent;
    private final List<String> queryParameters = new ArrayList<>();

    private Builder(AuthService auth, Access access, Action action) {
      this.auth = auth;
      this.access = access;
      this.action = action;
    }

    Builder body(BodySchema schema) {
      this.schema = schema;
      return this;
    }

    Builder optionalBody(BodySchema schema) {
      this.schema = schema;
      this.bodyOptional = true;
      return this;
    }

    Builder pathId(String name) {
      pathIds.add(name);
      return this;
    }

    Builder idempotent() {
      this.idempotent = true;
      return this;
    }

    Builder query(String... names) {
      queryParameters.addAll(List.of(names));
      return this;
    }

    Endpoint build() {
      return new Endpoint(this);
    }
  }

  @Override
  public void handle(Context ctx) throws Exception {
    byte[] body = readBody(ctx);
    Principal principal = authenticate(ctx);
    BodySchema.Validated fields = null;
    if (schema != null) {
      fields = schema.validate(body, bodyOptional);
    } else if (body.length > 0) {
      BodySchema.of().validate(body, true);
    }
    Map<String, String> ids = new LinkedHashMap<>();
    for (String name : pathIds) {
      ids.put(name, Ids.require(name, ctx.pathParam(name)));
    }
    String key = idempotent ? idempotencyKey(ctx) : null;
    Map<String, String> query = query(ctx);
    action.handle(new Call(ctx, principal, fields, ids, key, query));
  }

  /** Step 1: media type, then size. The body is read with a hard bound. */
  private static byte[] readBody(Context ctx) throws IOException {
    long declared = ctx.req().getContentLengthLong();
    byte[] body;
    if (declared > MAX_BODY_BYTES) {
      body = null;
    } else {
      body = readBounded(ctx.req().getInputStream());
    }
    boolean nonEmpty = body == null || body.length > 0;
    if (nonEmpty && "POST".equals(ctx.method().name())) {
      String type = ctx.req().getContentType();
      if (type == null || !MEDIA_TYPE.matcher(type.toLowerCase(Locale.ROOT)).matches()) {
        throw new LedgerException(
            ErrorCode.UNSUPPORTED_MEDIA_TYPE, "Request bodies must be application/json");
      }
    }
    if (body == null || body.length > MAX_BODY_BYTES) {
      throw new LedgerException(
          ErrorCode.PAYLOAD_TOO_LARGE, "Request body exceeds " + MAX_BODY_BYTES + " bytes");
    }
    return "POST".equals(ctx.method().name()) ? body : new byte[0];
  }

  private static byte[] readBounded(InputStream in) throws IOException {
    if (in == null) {
      return new byte[0];
    }
    return in.readNBytes(MAX_BODY_BYTES + 1);
  }

  /** Step 2: exactly one credential header, then the route role. */
  private Principal authenticate(Context ctx) {
    if (access == Access.PUBLIC) {
      return null;
    }
    List<String> bearer = headers(ctx, "Authorization");
    List<String> apiKey = headers(ctx, "X-API-Key");
    Principal principal;
    if (bearer.size() == 1 && apiKey.isEmpty()) {
      principal = auth.authenticateSession(bearerToken(bearer.get(0)));
    } else if (apiKey.size() == 1 && bearer.isEmpty()) {
      principal = auth.authenticateAdmin(apiKey.get(0));
    } else {
      throw new LedgerException(ErrorCode.UNAUTHENTICATED, "Authentication required");
    }
    boolean allowed =
        switch (access) {
          case ANY, PUBLIC -> true;
          case USER -> !principal.isAdmin();
          case ADMIN -> principal.isAdmin();
        };
    if (!allowed) {
      throw new LedgerException(ErrorCode.FORBIDDEN, "Forbidden");
    }
    return principal;
  }

  private static String bearerToken(String header) {
    if (header.length() > 7 && header.regionMatches(true, 0, "Bearer ", 0, 7)) {
      return header.substring(7);
    }
    return null;
  }

  private static List<String> headers(Context ctx, String name) {
    Enumeration<String> values = ctx.req().getHeaders(name);
    return values == null ? List.of() : Collections.list(values);
  }

  /** Step 3: the required idempotency header (FR-2.5). */
  private static String idempotencyKey(Context ctx) {
    List<String> values = headers(ctx, IDEMPOTENCY_HEADER);
    if (values.isEmpty()) {
      throw new LedgerException(
          ErrorCode.MISSING_IDEMPOTENCY_KEY, "Idempotency-Key header is required");
    }
    if (values.size() != 1 || !IDEMPOTENCY_KEY.matcher(values.get(0)).matches()) {
      throw new LedgerException(
          ErrorCode.INVALID_IDEMPOTENCY_KEY,
          "Idempotency-Key must be 16-64 letters, digits, '_' or '-'");
    }
    return values.get(0);
  }

  /** Unknown and repeated query parameters are rejected (NFR-SEC-1). */
  private Map<String, String> query(Context ctx) {
    Map<String, String> result = new LinkedHashMap<>();
    List<FieldError> problems = new ArrayList<>();
    for (Map.Entry<String, List<String>> p : ctx.queryParamMap().entrySet()) {
      if (!queryParameters.contains(p.getKey())) {
        problems.add(new FieldError(p.getKey(), "unknown"));
      } else if (p.getValue().size() != 1) {
        problems.add(new FieldError(p.getKey(), "duplicate"));
      } else {
        result.put(p.getKey(), p.getValue().get(0));
      }
    }
    if (!problems.isEmpty()) {
      throw new LedgerException(ErrorCode.VALIDATION_ERROR, "Invalid request", problems);
    }
    return result;
  }

  /** A request that passed the generic steps. */
  record Call(
      Context ctx,
      Principal principal,
      BodySchema.Validated body,
      Map<String, String> ids,
      String idempotencyKey,
      Map<String, String> query) {

    String id(String name) {
      return ids.get(name);
    }
  }
}
