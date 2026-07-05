package dev.stackverse.backend;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.json.JsonArray;
import jakarta.json.JsonObject;
import jakarta.json.JsonString;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
