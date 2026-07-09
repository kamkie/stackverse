package dev.stackverse.backend;

import io.micronaut.context.annotation.Property;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.client.HttpClient;
import io.micronaut.http.client.annotation.Client;
import io.micronaut.http.client.exceptions.HttpClientResponseException;
import io.micronaut.test.extensions.junit5.annotation.MicronautTest;
import jakarta.inject.Inject;
import jakarta.validation.Validator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

@MicronautTest(transactional = false)
@Property(name = "datasources.default.enabled", value = "false")
@Property(name = "flyway.datasources.default.enabled", value = "false")
final class MicronautHttpTest {
    @Inject
    @Client("/")
    HttpClient client;

    @Inject
    JwtVerifier verifier;

    @Inject
    AccountService accounts;

    @Inject
    Validator validator;

    @BeforeEach
    void configureIdentity() {
        reset(verifier, accounts);
        when(verifier.verify(anyString())).thenReturn(new Identity("demo", "Demo User", "demo@example.test", List.of()));
        when(accounts.recordSeen("demo")).thenReturn(new Account(
                "demo", Instant.EPOCH, Instant.EPOCH, Models.USER_ACTIVE, null, 0
        ));
    }

    @Test
    void routesAndSerializesFrameworkResponse() {
        var response = client.toBlocking().exchange(HttpRequest.GET("/healthz"), String.class);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
    }

    @Test
    void filterAndControllerReturnAuthenticatedIdentity() {
        var request = HttpRequest.GET("/api/v1/me").bearerAuth("valid-token");

        var response = client.toBlocking().exchange(request, String.class);

        assertThat(response.code()).isEqualTo(HttpStatus.OK.getCode());
        assertThat(response.body()).contains("\"username\":\"demo\"").contains("\"roles\":[]");
    }

    @Test
    void roleBoundaryUsesStackverseProblemResponse() {
        var request = HttpRequest.GET("/api/v1/admin/users").bearerAuth("valid-token");

        assertThatThrownBy(() -> client.toBlocking().exchange(request, String.class))
                .isInstanceOfSatisfying(HttpClientResponseException.class, failure -> {
                    assertThat(failure.getStatus().getCode()).isEqualTo(HttpStatus.FORBIDDEN.getCode());
                    assertThat(failure.getResponse().getContentType()).contains(MediaType.of("application/problem+json"));
                });
    }

    @Test
    void beanValidationKeepsContractMessageKeys() {
        var body = new BookmarkInput(
                "ftp://example.test",
                "   ",
                "x".repeat(4001),
                List.of("bad tag"),
                Models.PRIVATE
        );
        var request = HttpRequest.POST("/api/v1/bookmarks", body).bearerAuth("valid-token");

        assertThatThrownBy(() -> client.toBlocking().exchange(request, String.class))
                .isInstanceOfSatisfying(HttpClientResponseException.class, failure -> {
                    assertThat(failure.getStatus().getCode()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
                    String response = failure.getResponse().getBody(String.class).orElse("");
                    assertThat(response)
                            .contains("validation.url.invalid")
                            .contains("validation.title.required")
                            .contains("validation.notes.too-long")
                            .contains("validation.tag.invalid");
                });
    }

    @Test
    void beanValidationPreservesNormalizationBeforeDomainChecks() {
        BookmarkInput input = new BookmarkInput(
                "  https://example.test/path  ",
                "  A title  ",
                null,
                List.of(" TAG ", "tag"),
                Models.PUBLIC
        );

        assertThat(validator.validate(input)).isEmpty();
    }

}
