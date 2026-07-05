package dev.stackverse.backend;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Map;
import javax.sql.DataSource;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

final class AuthSupport {
    private AuthSupport() {}

    static Caller currentCaller(SecurityIdentity identity, JsonWebToken jwt) {
        if (identity == null || identity.isAnonymous()) {
            return null;
        }
        String username = claimString(jwt, "preferred_username");
        if (username == null || username.isBlank()) {
            username = identity.getPrincipal().getName();
        }
        return new Caller(
                username,
                new ArrayList<>(new LinkedHashSet<>(identity.getRoles())),
                claimString(jwt, "name"),
                claimString(jwt, "email"));
    }

    private static String claimString(JsonWebToken jwt, String claim) {
        Object value = jwt == null ? null : jwt.getClaim(claim);
        return value instanceof String string ? string : null;
    }
}

@Provider
class AccountRequestFilter implements ContainerRequestFilter {
    private static final Logger LOG = Logger.getLogger(AccountRequestFilter.class);

    private final DataSource dataSource;
    private final SecurityIdentity securityIdentity;
    private final JsonWebToken jwt;
    private final Localizer localizer;

    @Context UriInfo uriInfo;

    @Context HttpHeaders headers;

    @Inject
    AccountRequestFilter(
            DataSource dataSource,
            SecurityIdentity securityIdentity,
            JsonWebToken jwt,
            Localizer localizer) {
        this.dataSource = dataSource;
        this.securityIdentity = securityIdentity;
        this.jwt = jwt;
        this.localizer = localizer;
    }

    @Override
    public void filter(ContainerRequestContext requestContext) {
        Caller caller = AuthSupport.currentCaller(securityIdentity, jwt);
        if (caller == null) {
            return;
        }
        try (Connection connection = dataSource.getConnection()) {
            String status =
                    StackverseService.queryOne(
                                    connection,
                                    "insert into user_accounts (username, first_seen, last_seen, status)"
                                            + " values (?, ?, ?, 'active')"
                                            + " on conflict (username) do update set last_seen = excluded.last_seen"
                                            + " returning status",
                                    StackverseService.params(
                                            caller.username(),
                                            StackverseService.now(),
                                            StackverseService.now()),
                                    rs -> rs.getString("status"))
                            .orElse("active");
            if ("blocked".equals(status)) {
                StackverseLog.event(
                        LOG,
                        Logger.Level.WARN,
                        "blocked_user_rejected",
                        "denied",
                        "Refused a request from a blocked account",
                        Map.of("actor", caller.username()));
                requestContext.abortWith(
                        StackverseProblem.forbiddenKey("error.account.blocked")
                                .response(localizer, uriInfo, headers));
            }
        } catch (SQLException error) {
            throw new DbException(error);
        }
    }
}
