package dev.stackverse.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.quarkus.security.identity.SecurityIdentity;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.Proxy;
import java.security.Principal;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.junit.jupiter.api.Test;

class BackendSupportTest {
    private static final Instant CREATED = Instant.parse("2026-07-01T12:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-07-02T12:00:00Z");
    private static final UUID BOOKMARK_ID = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID REPORT_ID = UUID.fromString("66666666-7777-8888-9999-000000000000");

    @Test
    void parsesAcceptLanguageByQualityAndPrimarySubtag() {
        assertEquals(
                List.of("pl", "en"),
                Localizer.parseAcceptLanguage("en-US;q=0.5, pl-PL;q=0.9, *;q=1, de;q=0"));
    }

    @Test
    void parsesAcceptLanguageIgnoringInvalidAndZeroQualityEntries() {
        assertEquals(List.of(), Localizer.parseAcceptLanguage(null));
        assertEquals(
                List.of("en", "pl"),
                Localizer.parseAcceptLanguage("fr;q=bad, en-US;q=0.8, pl;q=0.8, *;q=1, de;q=0"));
    }

    @Test
    void cursorRoundTripsAsOpaqueBase64Url() {
        Cursor cursor =
                new Cursor(
                        Instant.parse("2026-07-01T12:34:56.123456Z"),
                        UUID.fromString("11111111-2222-3333-4444-555555555555"));

        assertEquals(cursor, Cursor.decode(cursor.encode()));
    }

    @Test
    void malformedCursorIsBadRequestProblem() {
        StackverseProblem problem =
                assertThrows(StackverseProblem.class, () -> Cursor.decode("not-a-cursor"));

        assertEquals(400, problem.status);
    }

    @Test
    void bookmarkVisibilityMatchesOwnershipAndPublicSurfaceRules() {
        Bookmark privateHidden = bookmark("alice", "private", "hidden");
        Bookmark publicActive = bookmark("alice", "public", "active");
        Bookmark publicHidden = bookmark("alice", "public", "hidden");

        assertTrue(privateHidden.visibleTo("alice"));
        assertTrue(publicActive.visibleTo("bob"));
        assertTrue(publicActive.visibleTo(null));
        assertFalse(privateHidden.visibleTo("bob"));
        assertFalse(publicHidden.visibleTo("bob"));
        assertFalse(publicHidden.visibleTo(null));
    }

    @Test
    void bookmarkResponseSortsTagsAndOmitsMissingOptionalFields() {
        Map<String, Object> response =
                StackverseService.bookmarkResponse(
                        new Bookmark(
                                BOOKMARK_ID,
                                "alice",
                                "https://example.com",
                                "Example",
                                null,
                                List.of("java", "api"),
                                "public",
                                "active",
                                CREATED,
                                UPDATED));

        assertEquals(BOOKMARK_ID.toString(), response.get("id"));
        assertEquals(List.of("api", "java"), response.get("tags"));
        assertEquals("2026-07-01T12:00:00Z", response.get("createdAt"));
        assertFalse(response.containsKey("notes"));
    }

    @Test
    void reportResponseIncludesResolutionFieldsOnlyWhenPresent() {
        Report open =
                new Report(
                        REPORT_ID,
                        BOOKMARK_ID,
                        "bob",
                        "spam",
                        null,
                        "open",
                        null,
                        null,
                        null,
                        CREATED);
        Report actioned =
                new Report(
                        REPORT_ID,
                        BOOKMARK_ID,
                        "bob",
                        "spam",
                        "duplicate",
                        "actioned",
                        "moderator",
                        UPDATED,
                        "hidden",
                        CREATED);

        Map<String, Object> openResponse = StackverseService.reportResponse(open);
        Map<String, Object> actionedResponse = StackverseService.reportResponse(actioned);

        assertFalse(openResponse.containsKey("comment"));
        assertFalse(openResponse.containsKey("resolvedAt"));
        assertEquals("duplicate", actionedResponse.get("comment"));
        assertEquals("moderator", actionedResponse.get("resolvedBy"));
        assertEquals("2026-07-02T12:00:00Z", actionedResponse.get("resolvedAt"));
        assertEquals("hidden", actionedResponse.get("resolutionNote"));
    }

    @Test
    void validatorThrowsProblemWithAllFieldViolations() {
        Validator validator = new Validator();
        validator.reject("url", "validation.url.invalid");
        validator.check(false, "title", "validation.title.required");

        StackverseProblem problem =
                assertThrows(StackverseProblem.class, validator::throwIfInvalid);

        assertEquals(400, problem.status);
        assertEquals(
                List.of(
                        new FieldViolation("url", "validation.url.invalid"),
                        new FieldViolation("title", "validation.title.required")),
                problem.fields);
    }

    @Test
    void problemResponseLocalizesDetailAndFieldErrors() {
        Response conflict =
                StackverseProblem.conflictKey("error.bookmark.hidden-publish")
                        .response(new StubLocalizer(), null, null);
        Response validation =
                StackverseProblem.validation(
                                List.of(
                                        new FieldViolation("url", "validation.url.invalid"),
                                        new FieldViolation("title", "validation.title.required")))
                        .response(new StubLocalizer(), null, null);

        Map<?, ?> conflictBody = assertInstanceOf(Map.class, conflict.getEntity());
        Map<?, ?> validationBody = assertInstanceOf(Map.class, validation.getEntity());
        List<?> errors = assertInstanceOf(List.class, validationBody.get("errors"));
        Map<?, ?> firstError = assertInstanceOf(Map.class, errors.get(0));

        assertEquals("application/problem+json", conflict.getMediaType().toString());
        assertEquals("pl:error.bookmark.hidden-publish", conflictBody.get("detail"));
        assertEquals("Request validation failed.", validationBody.get("detail"));
        assertEquals("url", firstError.get("field"));
        assertEquals("validation.url.invalid", firstError.get("messageKey"));
        assertEquals("pl:validation.url.invalid", firstError.get("message"));
    }

    @Test
    void sqlWhereBuildsStableWhereClauseAndParameterOrder() {
        SqlWhere empty = new SqlWhere();
        SqlWhere filtered = new SqlWhere();

        filtered.and("owner = ?", "alice");
        filtered.and("visibility = ?", "public");

        assertEquals("where true", empty.sql());
        assertEquals(List.of(), empty.params());
        assertEquals("where owner = ? and visibility = ?", filtered.sql());
        assertEquals(List.of("alice", "public"), filtered.params());
    }

    @Test
    void authSupportDerivesCallerFromJwtClaimsAndIdentityRoles() {
        Caller caller =
                AuthSupport.currentCaller(
                        identity(
                                false,
                                "subject-123",
                                new LinkedHashSet<>(List.of("moderator", "admin"))),
                        jwt(
                                Map.of(
                                        "preferred_username",
                                        "alice",
                                        "name",
                                        "Alice Doe",
                                        "email",
                                        "alice@example.com")));

        assertEquals("alice", caller.username());
        assertEquals(List.of("moderator", "admin"), caller.roles());
        assertEquals("Alice Doe", caller.name());
        assertEquals("alice@example.com", caller.email());
    }

    @Test
    void authSupportFallsBackToPrincipalNameAndIgnoresAnonymousIdentity() {
        Caller fallback =
                AuthSupport.currentCaller(
                        identity(false, "subject-123", Set.of()),
                        jwt(Map.of("preferred_username", "   ")));

        assertEquals("subject-123", fallback.username());
        assertNull(AuthSupport.currentCaller(identity(true, "anonymous", Set.of()), jwt(Map.of())));
    }

    @Test
    void routeHeadersAddDeprecationOnlyForGetV1BookmarksRoute() {
        Response routed =
                ResponseContracts.routeHeaders(
                        request("GET", "api/v1/bookmarks"), null, Response.status(400).build());
        Response post =
                ResponseContracts.routeHeaders(
                        request("POST", "api/v1/bookmarks"), null, Response.status(400).build());
        Response v2 =
                ResponseContracts.routeHeaders(
                        request("GET", "api/v2/bookmarks"), null, Response.status(400).build());

        assertEquals("@1782864000", routed.getHeaderString("Deprecation"));
        assertEquals("Thu, 01 Jul 2027 00:00:00 GMT", routed.getHeaderString("Sunset"));
        assertEquals(
                "</api/v2/bookmarks>; rel=\"successor-version\"", routed.getHeaderString("Link"));
        assertNull(post.getHeaderString("Deprecation"));
        assertNull(v2.getHeaderString("Deprecation"));
    }

    @Test
    void uniqueViolationDetectionWalksWrappedSqlCauses() {
        SQLException unique = new SQLException("duplicate key", "23505");
        SQLException other = new SQLException("connection failed", "08006");

        assertTrue(StackverseService.isUniqueViolation(new DbException(unique)));
        assertTrue(StackverseService.isUniqueViolation(new RuntimeException(unique)));
        assertFalse(StackverseService.isUniqueViolation(new DbException(other)));
        assertFalse(StackverseService.isUniqueViolation(new IllegalStateException("not SQL")));
    }

    private static Bookmark bookmark(String owner, String visibility, String status) {
        return new Bookmark(
                BOOKMARK_ID,
                owner,
                "https://example.com",
                "Example",
                "notes",
                List.of(),
                visibility,
                status,
                CREATED,
                UPDATED);
    }

    private static SecurityIdentity identity(
            boolean anonymous, String principalName, Set<String> roles) {
        return proxy(
                SecurityIdentity.class,
                (method, args) ->
                        switch (method.getName()) {
                            case "isAnonymous" -> anonymous;
                            case "getPrincipal" -> (Principal) () -> principalName;
                            case "getRoles" -> roles;
                            default -> defaultValue(method.getReturnType());
                        });
    }

    private static JsonWebToken jwt(Map<String, Object> claims) {
        return proxy(
                JsonWebToken.class,
                (method, args) ->
                        switch (method.getName()) {
                            case "getClaim" -> claims.get(String.valueOf(args[0]));
                            case "getName" -> claims.getOrDefault("preferred_username", "subject");
                            default -> defaultValue(method.getReturnType());
                        });
    }

    private static ContainerRequestContext request(String methodName, String path) {
        UriInfo uriInfo = uriInfo(path);
        return proxy(
                ContainerRequestContext.class,
                (method, args) ->
                        switch (method.getName()) {
                            case "getMethod" -> methodName;
                            case "getUriInfo" -> uriInfo;
                            default -> defaultValue(method.getReturnType());
                        });
    }

    private static UriInfo uriInfo(String path) {
        return proxy(
                UriInfo.class,
                (method, args) ->
                        switch (method.getName()) {
                            case "getPath" -> path;
                            default -> defaultValue(method.getReturnType());
                        });
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, Invocation invocation) {
        return (T)
                Proxy.newProxyInstance(
                        type.getClassLoader(),
                        new Class<?>[] {type},
                        (proxy, method, args) -> {
                            if ("toString".equals(method.getName())) {
                                return type.getSimpleName() + " test proxy";
                            }
                            if ("hashCode".equals(method.getName())) {
                                return System.identityHashCode(proxy);
                            }
                            if ("equals".equals(method.getName())) {
                                return proxy == args[0];
                            }
                            return invocation.invoke(method, args == null ? new Object[0] : args);
                        });
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive()) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        if (returnType == int.class) {
            return 0;
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == float.class) {
            return 0.0F;
        }
        if (returnType == double.class) {
            return 0.0D;
        }
        return null;
    }

    @FunctionalInterface
    private interface Invocation {
        Object invoke(java.lang.reflect.Method method, Object[] args) throws Throwable;
    }

    private static final class StubLocalizer extends Localizer {
        private StubLocalizer() {
            super(null);
        }

        @Override
        String resolveLanguage(UriInfo uriInfo, HttpHeaders headers) {
            return "pl";
        }

        @Override
        String localize(String key, String language) {
            return language + ":" + key;
        }

        @Override
        Map<String, String> localizeAll(Set<String> keys, String language) {
            Map<String, String> messages = new LinkedHashMap<>();
            for (String key : keys) {
                messages.put(key, localize(key, language));
            }
            return messages;
        }
    }
}
