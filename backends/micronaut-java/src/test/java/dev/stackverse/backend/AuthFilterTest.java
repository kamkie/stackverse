package dev.stackverse.backend;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuthFilterTest {
    @Test
    void missingAuthorizationContinuesFilterChain() {
        AuthFilter filter = new AuthFilter(null, null, null);

        assertThat(filter.authenticate(HttpRequest.GET("/readyz"))).isNull();
    }

    @Test
    void filterReturnIsMarkedNullableForMicronautContinueSignal() throws NoSuchMethodException {
        var method = AuthFilter.class.getDeclaredMethod("authenticate", HttpRequest.class);

        assertThat(method.isAnnotationPresent(Nullable.class)).isTrue();
    }
}
