package dev.stackverse.backend;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.Mock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@QuarkusTest
class QuarkusBoundaryTest {
    @Inject ObjectMapper mapper;

    @Test
    void servesLivenessThroughTheRunningQuarkusHttpBoundary() {
        given().when().get("/healthz").then().statusCode(200).body(equalTo(""));
    }

    @Test
    void authenticatedBookmarkWritesRejectAnonymousRequestsBeforeApplicationCode() {
        given().contentType("application/json")
                .body("{\"url\":\"https://example.com\",\"title\":\"Example\"}")
                .when()
                .post("/api/v1/bookmarks")
                .then()
                .statusCode(401)
                .contentType("application/problem+json")
                .body("title", equalTo("Unauthorized"));
    }

    @Test
    void adminMessageWritesRejectAnonymousRequestsBeforeApplicationCode() {
        given().contentType("application/json")
                .body("{\"key\":\"example.title\",\"language\":\"en\",\"text\":\"Example\"}")
                .when()
                .post("/api/v1/messages")
                .then()
                .statusCode(401)
                .contentType("application/problem+json")
                .body("title", equalTo("Unauthorized"));
    }

    @Test
    @TestSecurity(user = "reader")
    void adminMessageWritesRejectAuthenticatedCallersWithoutTheAdminRole() {
        given().contentType("application/json")
                .body("{\"key\":\"example.title\",\"language\":\"en\",\"text\":\"Example\"}")
                .when()
                .post("/api/v1/messages")
                .then()
                .statusCode(403)
                .contentType("application/problem+json")
                .body("title", equalTo("Forbidden"));
    }

    @Test
    @TestSecurity(user = "admin", roles = "admin")
    void beanValidationUsesTheLocalizedProblemContractAtTheLiveHttpBoundary() {
        given().contentType("application/json")
                .body("{\"key\":\"Not.Lower\",\"language\":\"english\",\"text\":\"\"}")
                .when()
                .post("/api/v1/messages")
                .then()
                .statusCode(400)
                .contentType("application/problem+json")
                .body("title", equalTo("Bad Request"))
                .body(
                        "errors.find { it.field == 'key' }.messageKey",
                        equalTo("validation.message.key.invalid"))
                .body(
                        "errors.find { it.field == 'language' }.messageKey",
                        equalTo("validation.message.language.invalid"))
                .body(
                        "errors.find { it.field == 'text' }.messageKey",
                        equalTo("validation.message.text.required"));
    }

    @Test
    void configuredJacksonSerializesTypedResponsesAndOmitsAbsentFields() throws Exception {
        BookmarkResponse response =
                new BookmarkResponse(
                        UUID.fromString("11111111-2222-3333-4444-555555555555"),
                        "https://example.com",
                        "Example",
                        null,
                        List.of("java"),
                        "public",
                        "active",
                        "alice",
                        Instant.parse("2026-07-01T12:00:00Z"),
                        Instant.parse("2026-07-02T12:00:00Z"));
        var json = mapper.readTree(mapper.writeValueAsString(response));

        assertEquals("11111111-2222-3333-4444-555555555555", json.get("id").asText());
        assertEquals("2026-07-01T12:00:00Z", json.get("createdAt").asText());
        assertFalse(json.has("notes"));
    }
}

@Mock
@ApplicationScoped
class BoundaryLocalizer extends Localizer {
    BoundaryLocalizer() {
        super(null);
    }

    @Override
    String resolveLanguage(UriInfo uriInfo, HttpHeaders headers) {
        return "en";
    }

    @Override
    Map<String, String> localizeAll(Set<String> keys, String language) {
        Map<String, String> messages = new LinkedHashMap<>();
        keys.forEach(key -> messages.put(key, key));
        return messages;
    }
}
