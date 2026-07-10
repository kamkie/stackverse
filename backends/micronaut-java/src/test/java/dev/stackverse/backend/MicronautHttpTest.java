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

    @BeforeEach
    void configureIdentity() {
        reset(verifier, accounts);
        when(verifier.verify(anyString())).thenAnswer(invocation -> {
            String token = invocation.getArgument(0);
            if ("admin-token".equals(token)) {
                return new Identity("admin", "Admin User", "admin@example.test", List.of("admin"));
            }
            return new Identity("demo", "Demo User", "demo@example.test", List.of());
        });
        when(accounts.recordSeen(anyString())).thenAnswer(invocation -> new Account(
                invocation.getArgument(0), Instant.EPOCH, Instant.EPOCH, Models.USER_ACTIVE, null, 0
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
    void micronautValidationKeepsOrderedContractMessageKeys() {
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
                    assertThat(response.indexOf("validation.url.invalid"))
                            .isLessThan(response.indexOf("validation.title.required"));
                    assertThat(response.indexOf("validation.title.required"))
                            .isLessThan(response.indexOf("validation.notes.too-long"));
                    assertThat(response.indexOf("validation.notes.too-long"))
                            .isLessThan(response.indexOf("validation.tag.invalid"));
                });
    }

    @Test
    void httpBoundaryPreservesNormalizationAndCodePointLimits() {
        BookmarkInput body = new BookmarkInput(
                "  https://example.test/path  ",
                "  A title  ",
                "😀".repeat(3000),
                List.of(" TAG ", "tag"),
                Models.PUBLIC
        );
        var request = HttpRequest.POST("/api/v1/bookmarks", body).bearerAuth("valid-token");

        var response = client.toBlocking().exchange(request, String.class);

        assertThat(response.code()).isEqualTo(HttpStatus.CREATED.getCode());
        assertThat(response.body())
                .contains("\"url\":\"https://example.test/path\"")
                .contains("\"title\":\"A title\"")
                .contains("\"tags\":[\"tag\"]");
    }

    @Test
    void authenticationPrecedesValidation() {
        BookmarkInput body = new BookmarkInput(null, null, null, null, null);

        assertThatThrownBy(() -> client.toBlocking().exchange(
                HttpRequest.POST("/api/v1/bookmarks", body), String.class))
                .isInstanceOfSatisfying(HttpClientResponseException.class, failure ->
                        assertThat(failure.getStatus().getCode()).isEqualTo(HttpStatus.UNAUTHORIZED.getCode()));
    }

    @Test
    void authenticationPrecedesMalformedAndWrongTypedJsonBinding() {
        for (String body : List.of("{", "{\"url\":false,\"title\":[]}")) {
            var anonymous = HttpRequest.POST("/api/v1/bookmarks", body)
                    .contentType(MediaType.APPLICATION_JSON_TYPE);
            assertThatThrownBy(() -> client.toBlocking().exchange(anonymous, String.class))
                    .isInstanceOfSatisfying(HttpClientResponseException.class, failure ->
                            assertThat(failure.getStatus().getCode())
                                    .isEqualTo(HttpStatus.UNAUTHORIZED.getCode()));

            var authenticated = HttpRequest.POST("/api/v1/bookmarks", body)
                    .contentType(MediaType.APPLICATION_JSON_TYPE)
                    .bearerAuth("valid-token");
            assertThatThrownBy(() -> client.toBlocking().exchange(authenticated, String.class))
                    .isInstanceOfSatisfying(HttpClientResponseException.class, failure ->
                            assertThat(failure.getStatus().getCode())
                                    .isEqualTo(HttpStatus.BAD_REQUEST.getCode()));
        }
    }

    @Test
    void roleCheckPrecedesValidation() {
        MessageInput body = new MessageInput(null, null, null, null);

        assertThatThrownBy(() -> client.toBlocking().exchange(
                HttpRequest.POST("/api/v1/messages", body).bearerAuth("valid-token"), String.class))
                .isInstanceOfSatisfying(HttpClientResponseException.class, failure ->
                        assertThat(failure.getStatus().getCode()).isEqualTo(HttpStatus.FORBIDDEN.getCode()));
    }

    @Test
    void roleCheckPrecedesMalformedJsonBinding() {
        var denied = HttpRequest.POST("/api/v1/messages", "{")
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .bearerAuth("valid-token");
        assertThatThrownBy(() -> client.toBlocking().exchange(denied, String.class))
                .isInstanceOfSatisfying(HttpClientResponseException.class, failure ->
                        assertThat(failure.getStatus().getCode()).isEqualTo(HttpStatus.FORBIDDEN.getCode()));

        var allowedToParse = HttpRequest.POST("/api/v1/messages", "{")
                .contentType(MediaType.APPLICATION_JSON_TYPE)
                .bearerAuth("admin-token");
        assertThatThrownBy(() -> client.toBlocking().exchange(allowedToParse, String.class))
                .isInstanceOfSatisfying(HttpClientResponseException.class, failure ->
                        assertThat(failure.getStatus().getCode()).isEqualTo(HttpStatus.BAD_REQUEST.getCode()));
    }

    @Test
    void messageValidationUsesFrameworkConstraints() {
        MessageInput body = new MessageInput("Invalid Key", "eng", "", "x".repeat(1001));
        var request = HttpRequest.POST("/api/v1/messages", body).bearerAuth("admin-token");

        assertThatThrownBy(() -> client.toBlocking().exchange(request, String.class))
                .isInstanceOfSatisfying(HttpClientResponseException.class, failure -> {
                    assertThat(failure.getStatus().getCode()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
                    assertThat(failure.getResponse().getBody(String.class).orElse(""))
                            .contains("validation.message.key.invalid")
                            .contains("validation.message.language.invalid")
                            .contains("validation.message.text.required")
                            .contains("validation.message.description.too-long");
                });
    }

    @Test
    void reportValidationUsesFrameworkConstraints() {
        ReportInput body = new ReportInput("phishing", "x".repeat(1001));
        var request = HttpRequest.PUT(
                "/api/v1/reports/00000000-0000-0000-0000-000000000456", body).bearerAuth("valid-token");

        assertThatThrownBy(() -> client.toBlocking().exchange(request, String.class))
                .isInstanceOfSatisfying(HttpClientResponseException.class, failure -> {
                    assertThat(failure.getStatus().getCode()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
                    assertThat(failure.getResponse().getBody(String.class).orElse(""))
                            .contains("validation.report.reason.invalid")
                            .contains("validation.report.comment.too-long");
                });
    }

}
