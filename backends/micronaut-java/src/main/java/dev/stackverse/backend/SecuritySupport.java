package dev.stackverse.backend;

import io.micronaut.http.HttpRequest;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Singleton
final class SecuritySupport {
    private static final Logger LOG = LoggerFactory.getLogger(SecuritySupport.class);

    Identity optional(HttpRequest<?> request) {
        return request.getAttribute(AuthFilter.IDENTITY, Identity.class).orElse(null);
    }

    Identity require(HttpRequest<?> request) {
        Identity identity = optional(request);
        if (identity == null) {
            throw Problems.unauthorized("Authentication is required.");
        }
        return identity;
    }

    Identity requireRole(HttpRequest<?> request, String role) {
        Identity identity = require(request);
        if (!identity.hasRole(role)) {
            EventLog.info(LOG, "authz_denied", "denied", "Denied a request lacking the required role",
                    Map.of("actor", identity.username(), "required_role", role));
            throw Problems.forbidden("You do not have the role required for this operation.");
        }
        return identity;
    }
}
