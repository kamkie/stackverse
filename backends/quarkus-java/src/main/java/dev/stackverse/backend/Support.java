package dev.stackverse.backend;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

record Caller(String username, List<String> roles, String name, String email) {
}

record FieldViolation(String field, String messageKey) {
}

final class StackverseProblem extends RuntimeException {
    final int status;
    final String title;
    final String detail;
    final String detailKey;
    final List<FieldViolation> fields;

    StackverseProblem(int status, String title, String detail, String detailKey, List<FieldViolation> fields) {
        super(detail == null ? title : detail);
        this.status = status;
        this.title = title;
        this.detail = detail;
        this.detailKey = detailKey;
        this.fields = fields == null ? List.of() : fields;
    }

    static StackverseProblem notFound() {
        return new StackverseProblem(404, "Not Found", null, null, null);
    }

    static StackverseProblem unauthorized(String detail) {
        return new StackverseProblem(401, "Unauthorized", detail, null, null);
    }

    static StackverseProblem forbidden(String detail) {
        return new StackverseProblem(403, "Forbidden", detail, null, null);
    }

    static StackverseProblem forbiddenKey(String key) {
        return new StackverseProblem(403, "Forbidden", null, key, null);
    }

    static StackverseProblem conflict(String detail) {
        return new StackverseProblem(409, "Conflict", detail, null, null);
    }

    static StackverseProblem conflictKey(String key) {
        return new StackverseProblem(409, "Conflict", null, key, null);
    }

    static StackverseProblem badRequest(String detail) {
        return new StackverseProblem(400, "Bad Request", detail, null, null);
    }

    static StackverseProblem validation(List<FieldViolation> fields) {
        return new StackverseProblem(400, "Bad Request", "Request validation failed.", null, fields);
    }

    Response response(Localizer localizer, UriInfo uriInfo, HttpHeaders headers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status);
        String language = null;
        String resolvedDetail = detail;
        if (detailKey != null) {
            language = localizer.resolveLanguage(uriInfo, headers);
            resolvedDetail = localizer.localize(detailKey, language);
        }
        StackverseResource.putIfPresent(body, "detail", resolvedDetail);
        if (!fields.isEmpty()) {
            if (language == null) {
                language = localizer.resolveLanguage(uriInfo, headers);
            }
            List<Map<String, Object>> errors = new ArrayList<>();
            for (FieldViolation field : fields) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("field", field.field());
                item.put("messageKey", field.messageKey());
                item.put("message", localizer.localize(field.messageKey(), language));
                errors.add(item);
            }
            body.put("errors", errors);
        }
        return Response.status(status).type("application/problem+json").entity(body).build();
    }
}

final class Validator {
    private final List<FieldViolation> fields = new ArrayList<>();

    void reject(String field, String messageKey) {
        fields.add(new FieldViolation(field, messageKey));
    }

    void check(boolean condition, String field, String messageKey) {
        if (!condition) {
            reject(field, messageKey);
        }
    }

    void throwIfInvalid() {
        if (!fields.isEmpty()) {
            throw StackverseProblem.validation(List.copyOf(fields));
        }
    }
}

final class AuthSupport {
    private AuthSupport() {
    }

    static Caller currentCaller(SecurityIdentity identity, JsonWebToken jwt) {
        if (identity == null || identity.isAnonymous()) {
            return null;
        }
        String username = claimString(jwt, "preferred_username");
        if (username == null || username.isBlank()) {
            username = identity.getPrincipal().getName();
        }
        Set<String> roles = new LinkedHashSet<>(identity.getRoles());
        roles.addAll(realmRoles(jwt));
        return new Caller(username, new ArrayList<>(roles),
                claimString(jwt, "name"), claimString(jwt, "email"));
    }

    private static String claimString(JsonWebToken jwt, String claim) {
        Object value = jwt == null ? null : jwt.getClaim(claim);
        return value instanceof String string ? string : null;
    }

    private static List<String> realmRoles(JsonWebToken jwt) {
        Object realmAccess = jwt == null ? null : jwt.getClaim("realm_access");
        Object roles = null;
        if (realmAccess instanceof Map<?, ?> map) {
            roles = map.get("roles");
        } else if (realmAccess instanceof JsonObject object) {
            roles = object.getJsonArray("roles");
        }
        List<String> result = new ArrayList<>();
        if (roles instanceof Iterable<?> iterable) {
            for (Object role : iterable) {
                if (role instanceof String string) {
                    result.add(string);
                } else if (role instanceof JsonString jsonString) {
                    result.add(jsonString.getString());
                } else if (role != null) {
                    result.add(String.valueOf(role));
                }
            }
        } else if (roles instanceof JsonArray array) {
            array.forEach(role -> result.add(role.toString().replace("\"", "")));
        }
        return result;
    }
}

@ApplicationScoped
class Localizer {
    static final String DEFAULT_LANGUAGE = "en";

    @Inject
    DataSource dataSource;

    String resolveLanguage(UriInfo uriInfo, HttpHeaders headers) {
        Set<String> supported = supportedLanguages();
        String explicit = firstParam(uriInfo.getQueryParameters().get("lang"));
        if (explicit != null && supported.contains(explicit)) {
            return explicit;
        }
        for (String language : parseAcceptLanguage(headers.getHeaderString(HttpHeaders.ACCEPT_LANGUAGE))) {
            if (supported.contains(language)) {
                return language;
            }
        }
        return DEFAULT_LANGUAGE;
    }

    String localize(String key, String language) {
        try (Connection connection = dataSource.getConnection()) {
            return StackverseResource.queryOne(connection,
                    "select text from messages where key = ? and language = any(?::text[])"
                            + " order by case when language = ? then 0 else 1 end limit 1",
                    List.of(key, List.of(language, DEFAULT_LANGUAGE), language),
                    rs -> rs.getString("text")).orElse(key);
        } catch (Exception error) {
            return key;
        }
    }

    Map<String, String> bundle(String language) {
        try (Connection connection = dataSource.getConnection()) {
            List<MessageText> rows = StackverseResource.query(connection,
                    "select key, language, text from messages where language = any(?::text[]) order by key",
                    List.of(List.of(language, DEFAULT_LANGUAGE)),
                    rs -> new MessageText(rs.getString("key"), rs.getString("language"), rs.getString("text")));
            Map<String, String> messages = new LinkedHashMap<>();
            for (MessageText row : rows) {
                if (row.language().equals(language) || !messages.containsKey(row.key())) {
                    messages.put(row.key(), row.text());
                }
            }
            return messages;
        } catch (SQLException error) {
            throw new DbException(error);
        }
    }

    Set<String> supportedLanguages() {
        try (Connection connection = dataSource.getConnection()) {
            return new LinkedHashSet<>(StackverseResource.query(connection,
                    "select distinct language from messages order by language",
                    List.of(),
                    rs -> rs.getString("language")));
        } catch (Exception error) {
            return Set.of(DEFAULT_LANGUAGE);
        }
    }

    static List<String> parseAcceptLanguage(String header) {
        if (header == null || header.isBlank()) {
            return List.of();
        }
        List<LanguagePreference> preferences = new ArrayList<>();
        String[] parts = header.split(",");
        for (int i = 0; i < parts.length; i++) {
            String part = parts[i].trim();
            if (part.isBlank()) {
                continue;
            }
            String[] segments = part.split(";");
            String tag = segments[0].trim();
            if (tag.isBlank() || tag.equals("*")) {
                continue;
            }
            double quality = 1.0;
            for (int j = 1; j < segments.length; j++) {
                String segment = segments[j].trim();
                if (segment.startsWith("q=")) {
                    try {
                        quality = Double.parseDouble(segment.substring(2).trim());
                    } catch (NumberFormatException ignored) {
                        quality = 0.0;
                    }
                }
            }
            if (quality <= 0) {
                continue;
            }
            String code = tag.toLowerCase(Locale.ROOT).split("-", 2)[0];
            if (!code.matches("^[a-z]{1,8}$")) {
                continue;
            }
            preferences.add(new LanguagePreference(code, quality, i));
        }
        preferences.sort(Comparator.comparingDouble(LanguagePreference::quality).reversed()
                .thenComparingInt(LanguagePreference::index));
        return preferences.stream().map(LanguagePreference::code).toList();
    }

    private static String firstParam(List<String> values) {
        return values == null || values.isEmpty() ? null : values.get(0);
    }
}

@Provider
@Priority(1)
class AuthenticationFailedMapper implements ExceptionMapper<AuthenticationFailedException> {
    private static final Logger LOG = Logger.getLogger(AuthenticationFailedMapper.class);

    @Inject
    Localizer localizer;

    @Context
    UriInfo uriInfo;

    @Context
    HttpHeaders headers;

    @Override
    public Response toResponse(AuthenticationFailedException exception) {
        StackverseLog.event(LOG, Logger.Level.INFO, "jwt_validation_failed", "failure",
                "Rejected a bearer token", Map.of("error_code", "invalid_token"));
        return StackverseProblem.unauthorized("Missing or invalid bearer token.")
                .response(localizer, uriInfo, headers);
    }
}

@Provider
class ProblemMapper implements ExceptionMapper<Throwable> {
    private static final Logger LOG = Logger.getLogger(ProblemMapper.class);

    @Inject
    Localizer localizer;

    @Context
    UriInfo uriInfo;

    @Context
    HttpHeaders headers;

    @Override
    public Response toResponse(Throwable throwable) {
        StackverseProblem problem = toProblem(throwable);
        if (!problem.fields.isEmpty()) {
            StackverseLog.event(LOG, Logger.Level.INFO, "input_validation_failed", "failure",
                    "Request validation failed",
                    Map.of("error_code", "validation_failed",
                            "fields", String.join(",", problem.fields.stream().map(FieldViolation::field).toList())));
        }
        if (problem.status >= 500) {
            LOG.error("Unhandled error serving request", throwable);
        }
        return problem.response(localizer, uriInfo, headers);
    }

    private StackverseProblem toProblem(Throwable throwable) {
        if (throwable instanceof StackverseProblem problem) {
            return problem;
        }
        if (throwable instanceof NotAuthorizedException) {
            StackverseLog.event(LOG, Logger.Level.INFO, "jwt_validation_failed", "failure",
                    "Rejected a bearer token", Map.of("error_code", "invalid_token"));
            return StackverseProblem.unauthorized("Missing or invalid bearer token.");
        }
        if (throwable instanceof ForbiddenException) {
            return StackverseProblem.forbidden("You do not have the role required for this operation.");
        }
        if (throwable instanceof NotFoundException) {
            return StackverseProblem.notFound();
        }
        if (throwable instanceof NotAllowedException) {
            return new StackverseProblem(405, "Method Not Allowed", null, null, null);
        }
        if (throwable instanceof BadRequestException) {
            return StackverseProblem.badRequest("Malformed request body.");
        }
        if (throwable instanceof WebApplicationException webApplicationException
                && webApplicationException.getResponse() != null
                && webApplicationException.getResponse().getStatus() >= 400
                && webApplicationException.getResponse().getStatus() < 500) {
            int status = webApplicationException.getResponse().getStatus();
            String title = status == 401 ? "Unauthorized"
                    : status == 403 ? "Forbidden"
                    : status == 404 ? "Not Found"
                    : status == 405 ? "Method Not Allowed"
                    : "Bad Request";
            return new StackverseProblem(status, title, null, null, null);
        }
        return new StackverseProblem(500, "Internal Server Error", null, null, null);
    }
}

@Provider
class AccountRequestFilter implements ContainerRequestFilter {
    private static final Logger LOG = Logger.getLogger(AccountRequestFilter.class);

    @Inject
    DataSource dataSource;

    @Inject
    SecurityIdentity securityIdentity;

    @Inject
    JsonWebToken jwt;

    @Inject
    Localizer localizer;

    @Context
    UriInfo uriInfo;

    @Context
    HttpHeaders headers;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        Caller caller = AuthSupport.currentCaller(securityIdentity, jwt);
        if (caller == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            String status = StackverseResource.queryOne(connection,
                    "insert into user_accounts (username, first_seen, last_seen, status)"
                            + " values (?, ?, ?, 'active')"
                            + " on conflict (username) do update set last_seen = excluded.last_seen"
                            + " returning status",
                    StackverseResource.params(caller.username(), StackverseResource.now(), StackverseResource.now()),
                    rs -> rs.getString("status")).orElse("active");
            if ("blocked".equals(status)) {
                StackverseLog.event(LOG, Logger.Level.WARN, "blocked_user_rejected", "denied",
                        "Refused a request from a blocked account", Map.of("actor", caller.username()));
                requestContext.abortWith(StackverseProblem.forbiddenKey("error.account.blocked")
                        .response(localizer, uriInfo, headers));
            }
        } catch (SQLException error) {
            throw new DbException(error);
        }
    }
}

final class StackverseLog {
    private StackverseLog() {
    }

    static void event(Logger logger, Logger.Level level, String event, String outcome,
                      String message, Map<String, ?> fields) {
        Map<String, Object> applied = new LinkedHashMap<>();
        applied.put("event", event);
        applied.put("outcome", outcome);
        if (fields != null) {
            applied.putAll(fields);
        }
        SpanContext span = Span.current().getSpanContext();
        if (span.isValid()) {
            applied.put("trace_id", span.getTraceId());
            applied.put("span_id", span.getSpanId());
        }
        try {
            applied.forEach(MDC::put);
            logger.log(level, message);
        } finally {
            applied.keySet().forEach(MDC::remove);
        }
    }
}

record MessageText(String key, String language, String text) {
}

record LanguagePreference(String code, double quality, int index) {
}
