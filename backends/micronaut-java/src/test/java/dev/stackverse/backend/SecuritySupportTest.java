package dev.stackverse.backend;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SecuritySupportTest {
    private final SecuritySupport security = new SecuritySupport();

    @Test
    void requireRejectsAnonymousRequests() {
        assertThatThrownBy(() -> security.require(HttpRequest.GET("/api/v1/me")))
                .isInstanceOfSatisfying(ProblemException.class, problem -> {
                    assertThat(problem.status.getCode()).isEqualTo(HttpStatus.UNAUTHORIZED.getCode());
                    assertThat(problem.detail).isEqualTo("Authentication is required.");
                });
    }

    @Test
    void requireRoleRejectsAuthenticatedCallerWithoutRole() {
        HttpRequest<?> request = authenticated(new Identity("demo", "Demo User", "demo@example.test", List.of("moderator")));

        assertThatThrownBy(() -> security.requireRole(request, "admin"))
                .isInstanceOfSatisfying(ProblemException.class, problem -> {
                    assertThat(problem.status.getCode()).isEqualTo(HttpStatus.FORBIDDEN.getCode());
                    assertThat(problem.detail).isEqualTo("You do not have the role required for this operation.");
                });
    }

    @Test
    void requireRoleReturnsIdentityWhenRoleIsPresent() {
        Identity admin = new Identity("admin", "Admin User", "admin@example.test", List.of("moderator", "admin"));
        HttpRequest<?> request = authenticated(admin);

        assertThat(security.requireRole(request, "admin")).isSameAs(admin);
    }

    private HttpRequest<?> authenticated(Identity identity) {
        HttpRequest<?> request = HttpRequest.GET("/api/v1/admin/messages");
        request.setAttribute(AuthFilter.IDENTITY, identity);
        return request;
    }
}
