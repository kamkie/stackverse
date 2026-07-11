package dev.stackverse.openliberty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Validation;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MultivaluedHashMap;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.SecurityContext;
import jakarta.ws.rs.core.UriInfo;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.Principal;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.flywaydb.core.Flyway;
import org.hibernate.validator.messageinterpolation.ParameterMessageInterpolator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.ArgumentCaptor;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Exercises contract-critical resource behavior against the production PostgreSQL schema. */
class OpenLibertyContractIntegrationTest {
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18-alpine")
                    .withDatabaseName("stackverse_open_liberty_test")
                    .withUsername("stackverse")
                    .withPassword("stackverse");

    private static jakarta.validation.Validator validator;

    private final Map<String, Object> attributes = new HashMap<>();
    private final Map<String, String> requestHeaders = new HashMap<>();
    private final MultivaluedMap<String, String> query = new MultivaluedHashMap<>();
    private final String[] requestPath = {"api/v1/bookmarks"};
    private final String[] requestMethod = {"GET"};

    private ContainerRuntime runtime;
    private MessageCatalog messages;
    private RecordingLog log;
    private HttpServletRequest request;
    private UriInfo uriInfo;
    private HttpHeaders headers;

    @BeforeAll
    static void startDatabase() {
        Assumptions.assumeTrue(
                DockerClientFactory.instance().isDockerAvailable(),
                "Docker is required for PostgreSQL integration coverage");
        POSTGRES.start();
        Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .locations("classpath:db/migration")
                .load()
                .migrate();
        validator =
                Validation.byDefaultProvider()
                        .configure()
                        .messageInterpolator(new ParameterMessageInterpolator())
                        .buildValidatorFactory()
                        .getValidator();
    }

    @AfterAll
    static void stopDatabase() {
        POSTGRES.stop();
    }

    @BeforeEach
    void resetDatabaseAndRequest() throws SQLException {
        runtime = new ContainerRuntime();
        try (Connection connection = runtime.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "truncate audit_entries, reports, bookmarks, messages, user_accounts cascade")) {
            statement.executeUpdate();
        }

        attributes.clear();
        requestHeaders.clear();
        query.clear();
        requestPath[0] = "api/v1/bookmarks";
        requestMethod[0] = "GET";

        request = mock(HttpServletRequest.class);
        when(request.getAttribute(anyString()))
                .thenAnswer(invocation -> attributes.get(invocation.getArgument(0, String.class)));
        when(request.getMethod()).thenAnswer(invocation -> requestMethod[0]);
        doAnswer(
                        invocation -> {
                            attributes.put(
                                    invocation.getArgument(0, String.class),
                                    invocation.getArgument(1));
                            return null;
                        })
                .when(request)
                .setAttribute(anyString(), org.mockito.ArgumentMatchers.any());
        doAnswer(
                        invocation -> {
                            attributes.remove(invocation.getArgument(0, String.class));
                            return null;
                        })
                .when(request)
                .removeAttribute(anyString());

        uriInfo = mock(UriInfo.class);
        when(uriInfo.getQueryParameters()).thenReturn(query);
        when(uriInfo.getPath()).thenAnswer(invocation -> requestPath[0]);

        headers = mock(HttpHeaders.class);
        when(headers.getHeaderString(anyString()))
                .thenAnswer(
                        invocation -> requestHeaders.get(invocation.getArgument(0, String.class)));

        messages = new MessageCatalog();
        messages.runtime = runtime;
        log = new RecordingLog();
    }

    @Test
    void reportingAndModerationPreserveVisibilityStateMachineAndAuditTrail() throws Exception {
        insertUser("alice");
        insertUser("bob");
        insertUser("carol");
        insertUser("moderator");

        Caller alice = caller("alice");
        Caller bob = caller("bob");
        Caller carol = caller("carol");
        Caller moderator = caller("moderator", "moderator");

        BookmarkResource bookmarks = wire(new BookmarkResource(), alice);
        Response created =
                bookmarks.create(
                        new BookmarkInput(
                                " https://example.com/article ",
                                "  Open Liberty article  ",
                                "contract notes",
                                List.of(" Java ", "java", "open-liberty"),
                                "public"));
        assertEquals(201, created.getStatus());
        JsonNode createdBody = body(created);
        UUID bookmarkId = UUID.fromString(createdBody.path("id").asText());
        assertEquals("/api/v1/bookmarks/" + bookmarkId, created.getHeaderString("Location"));
        assertEquals(List.of("java", "open-liberty"), strings(createdBody.path("tags")));
        assertEquals("Open Liberty article", createdBody.path("title").asText());

        query.add("tag", "java");
        query.add("q", "Liberty");
        Response listed = bookmarks.listV1();
        assertEquals(200, listed.getStatus());
        assertEquals("@1782864000", listed.getHeaderString("Deprecation"));
        assertEquals(1, body(listed).path("totalItems").asInt());
        query.clear();

        setCaller(null);
        assertEquals(200, bookmarks.get(bookmarkId.toString()).getStatus());

        ReportResource reports = wire(new ReportResource(), bob);
        Response firstReport =
                reports.create(bookmarkId.toString(), new ReportInput("spam", "suspicious"));
        UUID firstReportId = UUID.fromString(body(firstReport).path("id").asText());
        assertEquals(201, firstReport.getStatus());

        ApiProblem duplicate =
                assertThrows(
                        ApiProblem.class,
                        () ->
                                reports.create(
                                        bookmarkId.toString(),
                                        new ReportInput("offensive", "duplicate")));
        assertEquals(409, duplicate.status);

        Response revised =
                reports.updateMine(
                        firstReportId.toString(),
                        new ReportInput("broken-link", "target now returns 404"));
        assertEquals("broken-link", body(revised).path("reason").asText());

        setCaller(carol);
        Response siblingReport =
                reports.create(bookmarkId.toString(), new ReportInput("other", "second report"));
        UUID siblingReportId = UUID.fromString(body(siblingReport).path("id").asText());

        ModerationResource moderation = wire(new ModerationResource(), moderator);
        Response actioned =
                moderation.resolveReport(
                        firstReportId.toString(),
                        new ReportResolutionInput("actioned", "confirmed"));
        assertEquals("actioned", body(actioned).path("status").asText());
        assertEquals(
                "hidden", scalarString("select status from bookmarks where id = ?", bookmarkId));
        assertEquals(
                "actioned",
                scalarString("select status from reports where id = ?", siblingReportId));
        assertEquals(
                2L,
                scalarLong("select count(*) from audit_entries where action = 'report.resolved'"));
        assertEquals(
                1L,
                scalarLong(
                        "select count(*) from audit_entries where action = 'bookmark.status-changed'"));

        setCaller(caller("outsider"));
        assertEquals(
                404,
                assertThrows(ApiProblem.class, () -> bookmarks.get(bookmarkId.toString())).status);

        setCaller(alice);
        ApiProblem hiddenPublish =
                assertThrows(
                        ApiProblem.class,
                        () ->
                                bookmarks.update(
                                        bookmarkId.toString(),
                                        new BookmarkInput(
                                                "https://example.com/revised",
                                                "Revised",
                                                null,
                                                List.of("java"),
                                                "public")));
        assertEquals(409, hiddenPublish.status);
        assertEquals("error.bookmark.hidden-publish", hiddenPublish.detailKey);

        setCaller(moderator);
        Response restored =
                moderation.setBookmarkStatus(
                        bookmarkId.toString(), new BookmarkStatusInput("active", "restored"));
        assertEquals("active", body(restored).path("status").asText());

        Response reopened =
                moderation.resolveReport(
                        firstReportId.toString(), new ReportResolutionInput("open", "ignored"));
        JsonNode reopenedBody = body(reopened);
        assertEquals("open", reopenedBody.path("status").asText());
        assertFalse(reopenedBody.has("resolvedBy"));
        assertEquals(
                1L,
                scalarLong("select count(*) from audit_entries where action = 'report.reopened'"));

        query.add("status", "open");
        assertEquals(1, body(moderation.reports()).path("totalItems").asInt());
        query.clear();

        setCaller(bob);
        assertEquals(1, body(reports.listMine()).path("totalItems").asInt());
        assertEquals(204, reports.withdraw(firstReportId.toString()).getStatus());
        assertEquals(0L, scalarLong("select count(*) from reports where id = ?", firstReportId));

        assertTrue(log.eventsNamed("report_created") >= 2);
        assertTrue(log.eventsNamed("bookmark_status_changed") >= 1);
    }

    @Test
    void cursorPaginationTagsOwnershipAndIdentityUseTheContractShapes() throws Exception {
        insertUser("alice");
        Caller alice = caller("alice", "offline_access", "moderator", "uma_authorization");
        BookmarkResource bookmarks = wire(new BookmarkResource(), alice);

        Instant now = Instant.now().truncatedTo(ChronoUnit.MICROS);
        UUID newest =
                insertBookmark(
                        "alice", "Newest", List.of("java", "liberty"), "private", "active", now);
        UUID middle =
                insertBookmark(
                        "alice",
                        "Middle",
                        List.of("java"),
                        "private",
                        "active",
                        now.minusSeconds(1));
        UUID oldest =
                insertBookmark(
                        "alice",
                        "Oldest",
                        List.of("liberty"),
                        "private",
                        "active",
                        now.minusSeconds(2));

        query.add("size", "2");
        JsonNode firstPage = body(bookmarks.listV2());
        assertEquals(List.of(newest.toString(), middle.toString()), ids(firstPage.path("items")));
        String cursor = firstPage.path("nextCursor").asText();
        assertFalse(cursor.isBlank());

        query.putSingle("cursor", cursor);
        JsonNode secondPage = body(bookmarks.listV2());
        assertEquals(List.of(oldest.toString()), ids(secondPage.path("items")));
        assertFalse(secondPage.has("nextCursor"));
        assertNotEquals(
                firstPage.path("items").get(0).path("id").asText(),
                secondPage.path("items").get(0).path("id").asText());

        query.clear();
        JsonNode tagBody = body(bookmarks.tags());
        assertEquals("java", tagBody.path("tags").get(0).path("tag").asText());
        assertEquals(2, tagBody.path("tags").get(0).path("count").asInt());

        assertEquals(204, bookmarks.delete(middle.toString()).getStatus());
        assertEquals(
                404, assertThrows(ApiProblem.class, () -> bookmarks.get(middle.toString())).status);

        AccountResource account = wire(new AccountResource(), alice);
        JsonNode me = body(account.me());
        assertEquals("alice", me.path("username").asText());
        assertEquals(List.of("moderator"), strings(me.path("roles")));

        query.add("cursor", "not-a-cursor");
        assertEquals(400, assertThrows(ApiProblem.class, bookmarks::listV2).status);
    }

    @Test
    void runtimeMessagesUseLocalizedFallbackCachingAndAuditedAdminMutations() throws Exception {
        insertUser("admin");
        Caller admin = caller("admin", "admin", "moderator");
        MessageResource resource = wire(new MessageResource(), admin);

        Response english =
                resource.create(
                        new MessageInput(
                                "validation.title.required",
                                "en",
                                "Title is required",
                                "bookmark form"));
        UUID englishId = UUID.fromString(body(english).path("id").asText());
        resource.create(
                new MessageInput("validation.title.required", "pl", "Tytuł jest wymagany", null));
        Response fallback =
                resource.create(
                        new MessageInput(
                                "validation.notes.too-long", "en", "Notes are too long", null));
        UUID fallbackId = UUID.fromString(body(fallback).path("id").asText());

        ApiProblem duplicate =
                assertThrows(
                        ApiProblem.class,
                        () ->
                                resource.create(
                                        new MessageInput(
                                                "validation.title.required",
                                                "en",
                                                "Duplicate",
                                                null)));
        assertEquals(409, duplicate.status);

        query.add("q", "title");
        query.add("language", "en");
        Response listed = resource.list();
        assertEquals(200, listed.getStatus());
        assertEquals("no-cache", listed.getHeaderString("Cache-Control"));
        String listEtag = listed.getHeaderString("ETag");
        assertNotNull(listEtag);
        requestHeaders.put("If-None-Match", listEtag);
        assertEquals(304, resource.list().getStatus());

        requestHeaders.clear();
        query.clear();
        requestHeaders.put("Accept-Language", "de;q=0.2, en;q=0.7, pl-PL;q=0.9");
        assertEquals("pl", messages.resolveLanguage(null, requestHeaders.get("Accept-Language")));
        assertEquals("Notes are too long", messages.localize("validation.notes.too-long", "pl"));
        Response bundle = resource.bundle();
        JsonNode bundleBody = body(bundle);
        assertEquals("pl", bundle.getHeaderString("Content-Language"));
        assertEquals(
                "Tytuł jest wymagany",
                bundleBody.path("messages").path("validation.title.required").asText());
        assertEquals(
                "Notes are too long",
                bundleBody.path("messages").path("validation.notes.too-long").asText());

        requestHeaders.clear();
        Response found = resource.get(englishId.toString());
        assertEquals(200, found.getStatus());
        requestHeaders.put("If-None-Match", found.getHeaderString("ETag"));
        assertEquals(304, resource.get(englishId.toString()).getStatus());

        requestHeaders.clear();
        Response updated =
                resource.update(
                        englishId.toString(),
                        new MessageInput(
                                "validation.title.required", "en", "A title is required", null));
        assertEquals("A title is required", body(updated).path("text").asText());
        assertEquals(204, resource.delete(fallbackId.toString()).getStatus());
        assertEquals(
                404,
                assertThrows(ApiProblem.class, () -> resource.get(fallbackId.toString())).status);

        assertEquals(
                3L,
                scalarLong("select count(*) from audit_entries where action = 'message.created'"));
        assertEquals(
                1L,
                scalarLong("select count(*) from audit_entries where action = 'message.updated'"));
        assertEquals(
                1L,
                scalarLong("select count(*) from audit_entries where action = 'message.deleted'"));
        assertEquals(3, log.eventsNamed("message_created"));
    }

    @Test
    void adminAccountAuditAndStatsEndpointsEnforceRulesAndAggregatePersistedData()
            throws Exception {
        insertUser("admin");
        insertUser("target");
        insertUser("another");
        UUID publicBookmark =
                insertBookmark(
                        "target",
                        "Public",
                        List.of("java", "stats"),
                        "public",
                        "active",
                        Instant.now());
        insertBookmark(
                "another",
                "Hidden",
                List.of("java", "liberty"),
                "public",
                "hidden",
                Instant.now().minusSeconds(60));
        insertReport(publicBookmark, "another", "open");

        Caller admin = caller("admin", "admin", "moderator");
        AdminResource resource = wire(new AdminResource(), admin);

        query.add("q", "tar");
        query.add("status", "active");
        JsonNode users = body(resource.users());
        assertEquals(1, users.path("totalItems").asInt());
        assertEquals("target", users.path("items").get(0).path("username").asText());
        query.clear();

        assertEquals("target", body(resource.user("target")).path("username").asText());
        assertEquals(404, assertThrows(ApiProblem.class, () -> resource.user("missing")).status);

        ValidationProblem missingReason =
                assertThrows(
                        ValidationProblem.class,
                        () ->
                                resource.setUserStatus(
                                        "target", new UserStatusInput("blocked", null)));
        assertEquals(
                "validation.block.reason.required", missingReason.violations.get(0).messageKey());

        ApiProblem selfBlock =
                assertThrows(
                        ApiProblem.class,
                        () ->
                                resource.setUserStatus(
                                        "admin", new UserStatusInput("blocked", "not allowed")));
        assertEquals(409, selfBlock.status);

        JsonNode blocked =
                body(
                        resource.setUserStatus(
                                "target", new UserStatusInput("blocked", "policy breach")));
        assertEquals("blocked", blocked.path("status").asText());
        assertEquals("policy breach", blocked.path("blockedReason").asText());
        JsonNode active =
                body(resource.setUserStatus("target", new UserStatusInput("active", null)));
        assertEquals("active", active.path("status").asText());
        assertFalse(active.has("blockedReason"));

        query.add("actor", "admin");
        query.add("action", "user.blocked");
        JsonNode audit = body(resource.auditLog());
        assertEquals(1, audit.path("totalItems").asInt());
        assertEquals("target", audit.path("items").get(0).path("targetId").asText());
        query.clear();

        Response stats = resource.stats();
        JsonNode statsBody = body(stats);
        assertEquals(3, statsBody.path("totals").path("users").asInt());
        assertEquals(2, statsBody.path("totals").path("bookmarks").asInt());
        assertEquals(1, statsBody.path("totals").path("hiddenBookmarks").asInt());
        assertEquals(1, statsBody.path("totals").path("openReports").asInt());
        assertEquals(30, statsBody.path("daily").size());
        assertEquals("java", statsBody.path("topTags").get(0).path("tag").asText());

        requestHeaders.put("If-None-Match", stats.getHeaderString("ETag"));
        assertEquals(304, resource.stats().getStatus());
        assertEquals(
                1L, scalarLong("select count(*) from audit_entries where action = 'user.blocked'"));
        assertEquals(
                1L,
                scalarLong("select count(*) from audit_entries where action = 'user.unblocked'"));
        assertEquals(1, log.eventsNamed("user_blocked"));
        assertEquals(1, log.eventsNamed("user_unblocked"));
    }

    @Test
    void authenticationAndAuthorizationFiltersEnforceCallerAndRoleBoundaries() throws Exception {
        execute(
                "insert into messages (id, key, language, text, created_at, updated_at) values (?, ?, 'en', ?, ?, ?)",
                UUID.randomUUID(),
                "error.account.blocked",
                "This account is blocked.",
                Instant.now(),
                Instant.now());

        Map<String, String> inboundHeaders = new HashMap<>();
        ContainerRequestContext context = mock(ContainerRequestContext.class);
        when(context.getHeaderString(anyString()))
                .thenAnswer(
                        invocation -> inboundHeaders.get(invocation.getArgument(0, String.class)));
        when(context.getMethod()).thenReturn("GET");
        when(context.getUriInfo()).thenReturn(uriInfo);

        JsonWebToken jwt = mock(JsonWebToken.class);
        SecurityContext security = mock(SecurityContext.class);
        AuthFilter authentication = new AuthFilter();
        authentication.jwt = jwt;
        authentication.runtime = runtime;
        authentication.messages = messages;
        authentication.log = log;
        authentication.servletRequest = request;
        authentication.securityContext = security;

        setCaller(caller("stale"));
        authentication.filter(context);
        assertNull(attributes.get(AuthFilter.CALLER_ATTRIBUTE));
        verify(context, never()).abortWith(org.mockito.ArgumentMatchers.any());

        inboundHeaders.put("Authorization", "Bearer highly-sensitive-token");
        clearInvocations(context);
        authentication.filter(context);
        ArgumentCaptor<Response> aborted = ArgumentCaptor.forClass(Response.class);
        verify(context).abortWith(aborted.capture());
        assertEquals(401, aborted.getValue().getStatus());
        assertEquals("@1782864000", aborted.getValue().getHeaderString("Deprecation"));
        assertEquals(1, log.eventsNamed("jwt_validation_failed"));

        Principal principal = () -> "alice";
        when(security.getUserPrincipal()).thenReturn(principal);
        when(jwt.getName()).thenReturn("alice");
        when(jwt.<Object>getClaim("realm_access"))
                .thenReturn(Map.of("roles", List.of("moderator", "admin")));
        when(jwt.<Object>getClaim("name")).thenReturn("Alice Example");
        when(jwt.<Object>getClaim("email")).thenReturn("alice@example.com");
        clearInvocations(context);
        authentication.filter(context);
        verify(context, never()).abortWith(org.mockito.ArgumentMatchers.any());
        Caller authenticated = (Caller) attributes.get(AuthFilter.CALLER_ATTRIBUTE);
        assertEquals("alice", authenticated.username());
        assertEquals(List.of("admin", "moderator"), authenticated.roles());
        assertEquals(1L, scalarLong("select count(*) from user_accounts where username = 'alice'"));

        execute(
                "update user_accounts set status = 'blocked', blocked_reason = 'policy' where username = 'alice'");
        clearInvocations(context);
        authentication.filter(context);
        verify(context).abortWith(aborted.capture());
        Response blocked = aborted.getValue();
        assertEquals(403, blocked.getStatus());
        assertTrue(body(blocked).path("detail").asText().contains("blocked"));
        assertEquals(1, log.eventsNamed("blocked_user_rejected"));
        assertFalse(log.events.toString().contains("highly-sensitive-token"));

        CallerAuthorizationFilter callerFilter = new CallerAuthorizationFilter();
        callerFilter.request = request;
        setCaller(null);
        clearInvocations(context);
        callerFilter.filter(context);
        verify(context).abortWith(aborted.capture());
        assertEquals(401, aborted.getValue().getStatus());
        assertEquals("@1782864000", aborted.getValue().getHeaderString("Deprecation"));

        setCaller(caller("alice"));
        clearInvocations(context);
        callerFilter.filter(context);
        verify(context, never()).abortWith(org.mockito.ArgumentMatchers.any());

        RoleAuthorizationFilter roleFilter = new RoleAuthorizationFilter();
        roleFilter.log = log;
        roleFilter.request = request;
        ResourceInfo resourceInfo = mock(ResourceInfo.class);
        when(resourceInfo.getResourceMethod())
                .thenReturn(MessageResource.class.getMethod("create", MessageInput.class));
        doReturn(MessageResource.class).when(resourceInfo).getResourceClass();
        roleFilter.resourceInfo = resourceInfo;

        setCaller(null);
        clearInvocations(context);
        roleFilter.filter(context);
        verify(context).abortWith(aborted.capture());
        assertEquals(401, aborted.getValue().getStatus());

        setCaller(caller("moderator", "moderator"));
        clearInvocations(context);
        roleFilter.filter(context);
        verify(context).abortWith(aborted.capture());
        assertEquals(403, aborted.getValue().getStatus());
        assertEquals(1, log.eventsNamed("authz_denied"));

        setCaller(caller("admin", "admin"));
        clearInvocations(context);
        roleFilter.filter(context);
        verify(context, never()).abortWith(org.mockito.ArgumentMatchers.any());
    }

    @Test
    void problemMapperLocalizesExpectedFailuresAndSeparatesDependencyErrors() throws Exception {
        Instant now = Instant.now();
        execute(
                "insert into messages (id, key, language, text, created_at, updated_at) values (?, ?, 'pl', ?, ?, ?), (?, ?, 'pl', ?, ?, ?)",
                UUID.randomUUID(),
                "validation.title.required",
                "Tytuł jest wymagany",
                now,
                now,
                UUID.randomUUID(),
                "error.bookmark.hidden-publish",
                "Ukrytej zakładki nie można opublikować",
                now,
                now);

        ProblemMapper mapper = new ProblemMapper();
        mapper.messages = messages;
        mapper.log = log;
        mapper.uriInfo = uriInfo;
        mapper.headers = headers;
        mapper.request = request;
        query.add("lang", "pl");
        setCaller(caller("alice"));

        Response validation =
                mapper.toResponse(
                        new ValidationProblem(
                                List.of(new FieldViolation("title", "validation.title.required"))));
        assertEquals(400, validation.getStatus());
        assertEquals("@1782864000", validation.getHeaderString("Deprecation"));
        JsonNode validationBody = body(validation);
        assertEquals(
                "Tytuł jest wymagany",
                validationBody.path("errors").get(0).path("message").asText());
        assertEquals(1, log.eventsNamed("input_validation_failed"));
        assertEquals("alice", log.last("input_validation_failed").fields().get("actor"));

        Response localizedConflict =
                mapper.toResponse(
                        new RuntimeException(
                                ApiProblem.conflict("fallback", "error.bookmark.hidden-publish")));
        assertEquals(409, localizedConflict.getStatus());
        assertEquals(
                "Ukrytej zakładki nie można opublikować",
                body(localizedConflict).path("detail").asText());

        assertEquals(404, mapper.toResponse(new NotFoundException()).getStatus());
        assertEquals(405, mapper.toResponse(new NotAllowedException("GET")).getStatus());
        assertEquals(400, mapper.toResponse(new ProcessingException("bad json")).getStatus());
        assertEquals(
                403,
                mapper.toResponse(new WebApplicationException(Response.status(403).build()))
                        .getStatus());

        RequestTimingFilter timing = new RequestTimingFilter();
        timing.request = request;
        timing.filter(mock(ContainerRequestContext.class));
        assertTrue(attributes.get(RequestTimingFilter.STARTED_AT_ATTRIBUTE) instanceof Long);

        Response databaseFailure =
                mapper.toResponse(
                        new RuntimeException(new SQLException("database unavailable", "08006")));
        assertEquals(500, databaseFailure.getStatus());
        assertEquals(1, log.eventsNamed("dependency_call_failed"));
        assertEquals(
                "sqlstate_08006", log.last("dependency_call_failed").fields().get("error_code"));

        Response unexpected = mapper.toResponse(new IllegalStateException("boom"));
        assertEquals(500, unexpected.getStatus());
        assertEquals(1, log.eventsNamed("request_failed"));
        assertEquals("Unexpected server error.", body(unexpected).path("detail").asText());
    }

    @Test
    void runtimeLifecycleMigratesAndSeedsIdempotentlyWithoutLoggingCredentials(
            @TempDir Path seedDirectory) throws Exception {
        Files.writeString(
                seedDirectory.resolve("en.json"),
                "{\"validation.title.required\":\"Title is required\"}");
        Files.writeString(
                seedDirectory.resolve("pl.json"),
                "{\"validation.title.required\":\"Tytuł jest wymagany\"}");

        RecordingLog firstLog = new RecordingLog();
        RuntimeSupport first = new RuntimeSupport();
        first.config = new ContainerConfig(seedDirectory);
        first.log = firstLog;
        first.start();
        try {
            assertEquals(2L, scalarLong("select count(*) from messages"));
            assertEquals(1, firstLog.eventsNamed("db_migration_applied"));
            assertEquals(2, firstLog.eventsNamed("message_seed_imported"));
            assertEquals(1, firstLog.eventsNamed("application_start"));
            Map<String, ?> startupFields = firstLog.last("application_start").fields();
            assertFalse(startupFields.containsKey("db_password"));
            assertFalse(startupFields.containsKey("db_user"));
            assertEquals(POSTGRES.getHost(), startupFields.get("db_host"));
        } finally {
            first.stop();
        }
        assertEquals(1, firstLog.eventsNamed("application_stop"));

        RecordingLog secondLog = new RecordingLog();
        RuntimeSupport second = new RuntimeSupport();
        second.config = new ContainerConfig(seedDirectory);
        second.log = secondLog;
        second.start();
        try {
            assertEquals(2L, scalarLong("select count(*) from messages"));
            assertTrue(
                    secondLog.events.stream()
                            .filter(event -> event.event().equals("message_seed_imported"))
                            .allMatch(event -> event.fields().get("inserted").equals(0)));
        } finally {
            second.stop();
        }
    }

    private <T extends ResourceSupport> T wire(T resource, Caller caller) {
        resource.runtime = runtime;
        resource.messages = messages;
        resource.log = log;
        resource.beanValidator = validator;
        resource.request = request;
        resource.uriInfo = uriInfo;
        resource.headers = headers;
        setCaller(caller);
        return resource;
    }

    private void setCaller(Caller caller) {
        if (caller == null) {
            attributes.remove(AuthFilter.CALLER_ATTRIBUTE);
        } else {
            attributes.put(AuthFilter.CALLER_ATTRIBUTE, caller);
        }
    }

    private static Caller caller(String username, String... roles) {
        return new Caller(
                username, List.of(roles), username + " Example", username + "@example.com");
    }

    private static JsonNode body(Response response) throws Exception {
        Object entity = response.getEntity();
        assertTrue(entity instanceof String, "Expected a serialized JSON response entity");
        return JsonSupport.MAPPER.readTree((String) entity);
    }

    private static List<String> strings(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.asText()));
        return values;
    }

    private static List<String> ids(JsonNode array) {
        List<String> values = new ArrayList<>();
        array.forEach(value -> values.add(value.path("id").asText()));
        return values;
    }

    private void insertUser(String username) throws SQLException {
        execute(
                "insert into user_accounts (username, first_seen, last_seen, status) values (?, ?, ?, 'active')",
                username,
                Instant.now(),
                Instant.now());
    }

    private UUID insertBookmark(
            String owner,
            String title,
            List<String> tags,
            String visibility,
            String status,
            Instant createdAt)
            throws SQLException {
        UUID id = UUID.randomUUID();
        execute(
                "insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at) values (?, ?, ?, ?, ?, ?::text[], ?, ?, ?, ?)",
                id,
                owner,
                "https://example.com/" + id,
                title,
                null,
                tags.toArray(String[]::new),
                visibility,
                status,
                createdAt,
                createdAt);
        return id;
    }

    private void insertReport(UUID bookmarkId, String reporter, String status) throws SQLException {
        execute(
                "insert into reports (id, bookmark_id, reporter, reason, status, created_at) values (?, ?, ?, 'other', ?, ?)",
                UUID.randomUUID(),
                bookmarkId,
                reporter,
                status,
                Instant.now());
    }

    private void execute(String sql, Object... params) throws SQLException {
        try (Connection connection = runtime.connection();
                PreparedStatement statement = runtime.prepare(connection, sql, params)) {
            statement.executeUpdate();
        }
    }

    private long scalarLong(String sql, Object... params) throws SQLException {
        try (Connection connection = runtime.connection();
                PreparedStatement statement = runtime.prepare(connection, sql, params);
                ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getLong(1);
        }
    }

    private String scalarString(String sql, Object... params) throws SQLException {
        try (Connection connection = runtime.connection();
                PreparedStatement statement = runtime.prepare(connection, sql, params);
                ResultSet result = statement.executeQuery()) {
            assertTrue(result.next());
            return result.getString(1);
        }
    }

    private static final class ContainerRuntime extends RuntimeSupport {
        @Override
        Connection connection() throws SQLException {
            return DriverManager.getConnection(
                    POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        }
    }

    private static final class ContainerConfig extends AppConfig {
        private final Path seedDirectory;

        private ContainerConfig(Path seedDirectory) {
            this.seedDirectory = seedDirectory;
        }

        @Override
        String dbHost() {
            return POSTGRES.getHost();
        }

        @Override
        int dbPort() {
            return POSTGRES.getMappedPort(5432);
        }

        @Override
        String dbName() {
            return POSTGRES.getDatabaseName();
        }

        @Override
        String dbUser() {
            return POSTGRES.getUsername();
        }

        @Override
        String dbPassword() {
            return POSTGRES.getPassword();
        }

        @Override
        Path seedMessagesDir() {
            return seedDirectory;
        }
    }

    private static final class RecordingLog extends EventLogger {
        private final List<RecordedEvent> events = new ArrayList<>();

        @Override
        void event(
                String level, String event, String outcome, String message, Map<String, ?> fields) {
            events.add(
                    new RecordedEvent(level, event, outcome, message, new LinkedHashMap<>(fields)));
        }

        int eventsNamed(String name) {
            return (int) events.stream().filter(event -> event.event().equals(name)).count();
        }

        RecordedEvent last(String name) {
            return events.stream()
                    .filter(event -> event.event().equals(name))
                    .reduce((first, second) -> second)
                    .orElseThrow();
        }
    }

    private record RecordedEvent(
            String level, String event, String outcome, String message, Map<String, ?> fields) {}
}
