package dev.stackverse.backend;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import io.quarkus.test.Mock;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.security.TestSecurity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
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
    static final String PUBLIC_BOOKMARK = "11111111-2222-3333-4444-555555555551";
    static final String PRIVATE_BOOKMARK = "11111111-2222-3333-4444-555555555552";
    static final String HIDDEN_BOOKMARK = "11111111-2222-3333-4444-555555555553";

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
    void anonymousCallersCanReadPublicBookmarksById() {
        given().when()
                .get("/api/v1/bookmarks/{id}", PUBLIC_BOOKMARK)
                .then()
                .statusCode(200)
                .contentType("application/json")
                .body("id", equalTo(PUBLIC_BOOKMARK))
                .body("visibility", equalTo("public"))
                .body("status", equalTo("active"));
    }

    @Test
    void anonymousCallersSeePrivateBookmarksAsNotFound() {
        given().when()
                .get("/api/v1/bookmarks/{id}", PRIVATE_BOOKMARK)
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("title", equalTo("Not Found"));
    }

    @Test
    void anonymousCallersSeeHiddenBookmarksAsNotFound() {
        given().when()
                .get("/api/v1/bookmarks/{id}", HIDDEN_BOOKMARK)
                .then()
                .statusCode(404)
                .contentType("application/problem+json")
                .body("title", equalTo("Not Found"));
    }

    @Test
    @TestSecurity(user = "alice")
    void authenticatedOwnersCanReadTheirPrivateAndHiddenBookmarks() {
        given().when()
                .get("/api/v1/bookmarks/{id}", PRIVATE_BOOKMARK)
                .then()
                .statusCode(200)
                .body("visibility", equalTo("private"));
        given().when()
                .get("/api/v1/bookmarks/{id}", HIDDEN_BOOKMARK)
                .then()
                .statusCode(200)
                .body("status", equalTo("hidden"));
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

@Mock
@ApplicationScoped
class BoundaryBookmarkService extends BookmarkService {
    @Inject SecurityIdentity securityIdentity;

    BoundaryBookmarkService() {
        super(null, null, null, null, null);
    }

    @Override
    public Response getBookmark(String rawId) {
        boolean owner =
                !securityIdentity.isAnonymous()
                        && "alice".equals(securityIdentity.getPrincipal().getName());
        if (QuarkusBoundaryTest.PUBLIC_BOOKMARK.equals(rawId)) {
            return bookmark(rawId, "public", "active");
        }
        if (owner && QuarkusBoundaryTest.PRIVATE_BOOKMARK.equals(rawId)) {
            return bookmark(rawId, "private", "active");
        }
        if (owner && QuarkusBoundaryTest.HIDDEN_BOOKMARK.equals(rawId)) {
            return bookmark(rawId, "public", "hidden");
        }
        throw StackverseProblem.notFound();
    }

    private static Response bookmark(String rawId, String visibility, String status) {
        Instant now = Instant.parse("2026-07-01T12:00:00Z");
        return Response.ok(
                        new BookmarkResponse(
                                UUID.fromString(rawId),
                                "https://example.com/" + rawId,
                                "Boundary bookmark",
                                null,
                                List.of(),
                                visibility,
                                status,
                                "alice",
                                now,
                                now))
                .build();
    }
}
