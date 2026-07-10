package dev.stackverse.backend;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import java.util.Map;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

@ApplicationScoped
final class Authorization {
    private static final Logger LOG = Logger.getLogger(Authorization.class);

    private final SecurityIdentity securityIdentity;
    private final JsonWebToken jwt;

    Authorization(SecurityIdentity securityIdentity, JsonWebToken jwt) {
        this.securityIdentity = securityIdentity;
        this.jwt = jwt;
    }

    Caller currentCaller() {
        return AuthSupport.currentCaller(securityIdentity, jwt);
    }

    Caller requireCaller() {
        Caller caller = currentCaller();
        if (caller == null) {
            throw StackverseProblem.unauthorized("Authentication is required.");
        }
        return caller;
    }

    Caller requireRole(String role) {
        Caller caller = requireCaller();
        if (!caller.roles().contains(role)) {
            StackverseLog.event(
                    LOG,
                    Logger.Level.INFO,
                    "authz_denied",
                    "denied",
                    "Denied a request lacking the required role",
                    Map.of("actor", caller.username()));
            throw StackverseProblem.forbidden(
                    "You do not have the role required for this operation.");
        }
        return caller;
    }
}
