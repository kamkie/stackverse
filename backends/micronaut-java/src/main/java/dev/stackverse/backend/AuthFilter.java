package dev.stackverse.backend;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.RequestFilter;
import io.micronaut.http.annotation.ServerFilter;
import io.micronaut.http.server.annotation.PreMatching;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;
import java.util.regex.Pattern;

@ServerFilter("/**")
final class AuthFilter {
    static final String IDENTITY = "stackverse.identity";
    private static final Logger LOG = LoggerFactory.getLogger(AuthFilter.class);
    private static final Pattern BOOKMARK_REPORT_CREATE =
            Pattern.compile("/api/v1/bookmarks/[^/]+/reports");
    private static final Pattern CALLER_OWNED_ITEM =
            Pattern.compile("/api/v1/(bookmarks|reports)/[^/]+");
    private static final Pattern MESSAGE_WRITE =
            Pattern.compile("/api/v1/messages(?:/[^/]+)?");
    private static final Pattern ADMIN_USER_STATUS =
            Pattern.compile("/api/v1/admin/users/[^/]+/status");
    private static final Pattern MODERATOR_WRITE =
            Pattern.compile("/api/v1/admin/(reports/[^/]+|bookmarks/[^/]+/status)");

    private final JwtVerifier verifier;
    private final AccountService accounts;
    private final ProblemHandler problems;
    private final SecuritySupport security;

    AuthFilter(JwtVerifier verifier, AccountService accounts, ProblemHandler problems, SecuritySupport security) {
        this.verifier = verifier;
        this.accounts = accounts;
        this.problems = problems;
        this.security = security;
    }

    @RequestFilter
    @PreMatching
    @ExecuteOn(TaskExecutors.BLOCKING)
    @Nullable
    MutableHttpResponse<?> authenticate(HttpRequest<?> request) {
        AccessRule access = accessRule(request.getMethod(), request.getPath());
        String header = request.getHeaders().get("Authorization");
        if (header == null || header.isBlank()) {
            return enforceAccess(request, access);
        }
        if (!header.startsWith("Bearer ")) {
            return problem(request, Problems.unauthorized("Missing or invalid bearer token."));
        }
        Identity identity;
        try {
            identity = verifier.verify(header.substring("Bearer ".length()));
        } catch (IllegalArgumentException ex) {
            EventLog.info(LOG, "jwt_validation_failed", "failure", "Rejected a bearer token",
                    Map.of("error_code", "invalid_token"));
            return problem(request, Problems.unauthorized("Missing or invalid bearer token."));
        }
        request.setAttribute(IDENTITY, identity);
        Account account = accounts.recordSeen(identity.username());
        if (Models.USER_BLOCKED.equals(account.status())) {
            EventLog.warn(LOG, "blocked_user_rejected", "denied", "Refused a request from a blocked account",
                    Map.of("actor", identity.username()));
            return problem(request, Problems.forbiddenKey("error.account.blocked"));
        }
        return enforceAccess(request, access);
    }

    private MutableHttpResponse<?> enforceAccess(HttpRequest<?> request, AccessRule access) {
        try {
            if (access.role() != null) {
                security.requireRole(request, access.role());
            } else if (access.authenticated()) {
                security.require(request);
            }
            return null;
        } catch (ProblemException ex) {
            return problem(request, ex);
        }
    }

    static AccessRule accessRule(HttpMethod method, String path) {
        if (method == HttpMethod.POST && "/api/v1/bookmarks".equals(path)) {
            return AccessRule.AUTHENTICATED;
        }
        if (method == HttpMethod.POST && BOOKMARK_REPORT_CREATE.matcher(path).matches()) {
            return AccessRule.AUTHENTICATED;
        }
        if ((method == HttpMethod.PUT || method == HttpMethod.DELETE)
                && CALLER_OWNED_ITEM.matcher(path).matches()) {
            return AccessRule.AUTHENTICATED;
        }
        if (MESSAGE_WRITE.matcher(path).matches()
                && (method == HttpMethod.POST || method == HttpMethod.PUT || method == HttpMethod.DELETE)) {
            return AccessRule.ADMIN;
        }
        if (method == HttpMethod.PUT && ADMIN_USER_STATUS.matcher(path).matches()) {
            return AccessRule.ADMIN;
        }
        if (method == HttpMethod.PUT && MODERATOR_WRITE.matcher(path).matches()) {
            return AccessRule.MODERATOR;
        }
        return AccessRule.PUBLIC;
    }

    private MutableHttpResponse<?> problem(HttpRequest<?> request, ProblemException problem) {
        return problems.response(request, problem);
    }
}

record AccessRule(boolean authenticated, String role) {
    static final AccessRule PUBLIC = new AccessRule(false, null);
    static final AccessRule AUTHENTICATED = new AccessRule(true, null);
    static final AccessRule ADMIN = new AccessRule(true, "admin");
    static final AccessRule MODERATOR = new AccessRule(true, "moderator");
}
