package dev.stackverse.backend;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.server.annotation.PreMatching;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFilterTest {
    @Test
    void missingAuthorizationContinuesFilterChain() {
        AuthFilter filter = new AuthFilter(null, null, null, null);

        assertThat(filter.authenticate(HttpRequest.GET("/readyz"))).isNull();
    }

    @Test
    void filterReturnIsMarkedNullableForMicronautContinueSignal() throws NoSuchMethodException {
        var method = AuthFilter.class.getDeclaredMethod("authenticate", HttpRequest.class);

        assertThat(method.isAnnotationPresent(Nullable.class)).isTrue();
        assertThat(method.isAnnotationPresent(PreMatching.class)).isTrue();
    }

    @Test
    void protectedWritePolicyCoversCallerAndRoleBoundariesWithoutCapturingPublicRoutes() {
        assertThat(AuthFilter.accessRule(HttpMethod.POST, "/api/v1/bookmarks"))
                .isEqualTo(AccessRule.AUTHENTICATED);
        assertThat(AuthFilter.accessRule(HttpMethod.PUT, "/api/v1/bookmarks/id"))
                .isEqualTo(AccessRule.AUTHENTICATED);
        assertThat(AuthFilter.accessRule(HttpMethod.DELETE, "/api/v1/bookmarks/id"))
                .isEqualTo(AccessRule.AUTHENTICATED);
        assertThat(AuthFilter.accessRule(HttpMethod.POST, "/api/v1/bookmarks/id/reports"))
                .isEqualTo(AccessRule.AUTHENTICATED);
        assertThat(AuthFilter.accessRule(HttpMethod.PUT, "/api/v1/reports/id"))
                .isEqualTo(AccessRule.AUTHENTICATED);
        assertThat(AuthFilter.accessRule(HttpMethod.DELETE, "/api/v1/reports/id"))
                .isEqualTo(AccessRule.AUTHENTICATED);
        assertThat(AuthFilter.accessRule(HttpMethod.POST, "/api/v1/messages"))
                .isEqualTo(AccessRule.ADMIN);
        assertThat(AuthFilter.accessRule(HttpMethod.PUT, "/api/v1/messages/id"))
                .isEqualTo(AccessRule.ADMIN);
        assertThat(AuthFilter.accessRule(HttpMethod.DELETE, "/api/v1/messages/id"))
                .isEqualTo(AccessRule.ADMIN);
        assertThat(AuthFilter.accessRule(HttpMethod.PUT, "/api/v1/admin/users/name/status"))
                .isEqualTo(AccessRule.ADMIN);
        assertThat(AuthFilter.accessRule(HttpMethod.PUT, "/api/v1/admin/reports/id"))
                .isEqualTo(AccessRule.MODERATOR);
        assertThat(AuthFilter.accessRule(HttpMethod.PUT, "/api/v1/admin/bookmarks/id/status"))
                .isEqualTo(AccessRule.MODERATOR);
        assertThat(AuthFilter.accessRule(HttpMethod.GET, "/api/v1/messages"))
                .isEqualTo(AccessRule.PUBLIC);
        assertThat(AuthFilter.accessRule(HttpMethod.GET, "/healthz"))
                .isEqualTo(AccessRule.PUBLIC);
        assertThat(AuthFilter.accessRule(HttpMethod.POST, "/not-a-route"))
                .isEqualTo(AccessRule.PUBLIC);
    }
}
