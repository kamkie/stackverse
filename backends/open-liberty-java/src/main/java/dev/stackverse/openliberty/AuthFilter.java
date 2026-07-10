package dev.stackverse.openliberty;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.ext.Provider;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.jwt.JsonWebToken;

/**
 * Adds Stackverse account state after Open Liberty has authenticated the bearer token.
 *
 * <p>Signature, issuer, audience, lifetime, and principal validation belong to Liberty's
 * MicroProfile JWT implementation. This filter only translates the authenticated identity into the
 * application caller and enforces the Stackverse-specific blocked-account rule.
 */
@Provider
@Priority(Priorities.AUTHENTICATION + 100)
@RequestScoped
public class AuthFilter implements ContainerRequestFilter {
    static final String CALLER_ATTRIBUTE = "stackverse.caller";

    @Inject JsonWebToken jwt;
    @Inject RuntimeSupport runtime;
    @Inject MessageCatalog messages;
    @Inject EventLogger log;

    @Context HttpServletRequest servletRequest;
    @Context SecurityContext securityContext;

    @Override
    public void filter(ContainerRequestContext request) {
        servletRequest.removeAttribute(CALLER_ATTRIBUTE);
        String header = request.getHeaderString("Authorization");
        if (header == null || !header.startsWith("Bearer ")) {
            return;
        }

        if (securityContext.getUserPrincipal() == null || jwt.getName() == null) {
            log.event(
                    "info",
                    "jwt_validation_failed",
                    "failure",
                    "Rejected a bearer token",
                    Map.of("error_code", "invalid_token"));
            request.abortWith(
                    withRouteHeaders(
                            request,
                            JsonSupport.problem(
                                    401,
                                    "Unauthorized",
                                    "Missing or invalid bearer token.",
                                    null)));
            return;
        }

        List<String> roles = realmRoles(jwt.getClaim("realm_access"));
        Caller caller =
                new Caller(jwt.getName(), roles, jwt.getClaim("name"), jwt.getClaim("email"));
        AccountState state = recordSeen(caller.username());
        if ("blocked".equals(state.status())) {
            log.event(
                    "warn",
                    "blocked_user_rejected",
                    "denied",
                    "Refused a request from a blocked account",
                    Map.of("actor", caller.username()));
            String language =
                    messages.resolveLanguage(
                            messages.firstParam(
                                    request.getUriInfo().getQueryParameters().get("lang")),
                            request.getHeaderString("Accept-Language"));
            request.abortWith(
                    withRouteHeaders(
                            request,
                            JsonSupport.problem(
                                    403,
                                    "Forbidden",
                                    messages.localize("error.account.blocked", language),
                                    null)));
            return;
        }
        servletRequest.setAttribute(CALLER_ATTRIBUTE, caller);
    }

    private static Response withRouteHeaders(ContainerRequestContext request, Response response) {
        if (DeprecationHeaders.isDeprecatedV1Bookmarks(
                request.getMethod(), request.getUriInfo().getPath())) {
            return DeprecationHeaders.addV1BookmarkHeaders(response);
        }
        return response;
    }

    static List<String> realmRoles(Object realmAccess) {
        List<String> roles = new ArrayList<>();
        if (realmAccess instanceof JsonObject object) {
            JsonArray rawRoles = object.getJsonArray("roles");
            if (rawRoles != null) {
                rawRoles.stream()
                        .filter(JsonString.class::isInstance)
                        .map(JsonString.class::cast)
                        .map(JsonString::getString)
                        .forEach(roles::add);
            }
        } else if (realmAccess instanceof Map<?, ?> map
                && map.get("roles") instanceof List<?> rawRoles) {
            rawRoles.stream()
                    .filter(String.class::isInstance)
                    .map(String.class::cast)
                    .forEach(roles::add);
        }
        roles.sort(Comparator.naturalOrder());
        return roles;
    }

    private AccountState recordSeen(String username) {
        try (Connection connection = runtime.connection();
                PreparedStatement statement =
                        runtime.prepare(
                                connection,
                                """
                insert into user_accounts (username, first_seen, last_seen, status)
                values (?, ?, ?, 'active')
                on conflict (username) do update set last_seen = excluded.last_seen
                returning status
                """,
                                username,
                                Instant.now(),
                                Instant.now())) {
            try (var rs = statement.executeQuery()) {
                rs.next();
                return new AccountState(rs.getString("status"));
            }
        } catch (Exception ex) {
            throw new RuntimeException(ex);
        }
    }
}

record Caller(String username, List<String> roles, String name, String email) {
    boolean hasRole(String role) {
        return roles.contains(role);
    }
}

record AccountState(String status) {}
