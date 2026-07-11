package dev.stackverse.backend;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import io.micronaut.http.MutableHttpResponse;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.sql.Connection;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

final class PostgresIntegrationTest {
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(DockerImageName.parse("postgres:18-alpine"));

    private static HikariDataSource dataSource;

    private Database db;
    private ObjectMapper mapper;
    private SecuritySupport security;
    private AccountService accounts;
    private BookmarksController bookmarks;
    private MessageCatalog catalog;
    private MessagesController messages;
    private ModerationController moderation;
    private AdminController admin;

    @BeforeAll
    static void startPostgres() {
        POSTGRES.start();
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(POSTGRES.getJdbcUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(4);
        dataSource = new HikariDataSource(config);
        Flyway.configure().dataSource(dataSource).load().migrate();
    }

    @AfterAll
    static void stopPostgres() {
        if (dataSource != null) {
            dataSource.close();
        }
        POSTGRES.stop();
    }

    @BeforeEach
    void setUp() {
        db = new Database(dataSource);
        db.update("truncate table audit_entries, reports, bookmarks, messages, user_accounts cascade");
        mapper = new ObjectMapper();
        security = new SecuritySupport();
        accounts = new AccountService(db);
        AuditService audit = new AuditService(db, mapper);
        InputValidator inputs = mock(InputValidator.class);
        bookmarks = new BookmarksController(db, security, inputs);
        catalog = new MessageCatalog(db, mapper, ".");
        messages = new MessagesController(db, catalog, audit, security, mapper, inputs);
        moderation = new ModerationController(db, security, audit, bookmarks, inputs);
        admin = new AdminController(db, security, accounts, audit, mapper);
    }

    @Test
    void bookmarkPersistenceEnforcesOwnershipFiltersAndStableCursors() {
        BookmarkResponse alicePrivate = createBookmark(
                "alice", "  https://example.test/private  ", "  Private Java  ",
                List.of(" Java ", "java"), Models.PRIVATE);
        BookmarkResponse alicePublic = createBookmark(
                "alice", "https://example.test/public-a", "Public Java", List.of("java", "web"), Models.PUBLIC);
        BookmarkResponse bobPublic = createBookmark(
                "bob", "https://example.test/public-b", "Public Micronaut", List.of("java", "micronaut"), Models.PUBLIC);

        assertThat(alicePrivate.url()).isEqualTo("https://example.test/private");
        assertThat(alicePrivate.title()).isEqualTo("Private Java");
        assertThat(alicePrivate.tags()).containsExactly("java");

        MutableHttpRequest<?> mine = caller("alice", "/api/v1/bookmarks");
        mine.getParameters().add("tag", "JAVA");
        mine.getParameters().add("q", "java");
        MutableHttpResponse<PageResponse<BookmarkResponse>> ownPage = bookmarks.listV1(mine);
        assertThat(ownPage.getHeaders().get("Deprecation")).isEqualTo(WebSupport.DEPRECATION);
        assertThat(ownPage.getHeaders().get("Sunset")).isEqualTo(WebSupport.SUNSET);
        assertThat(ownPage.getBody().orElseThrow().items())
                .extracting(BookmarkResponse::id)
                .containsExactlyInAnyOrder(alicePrivate.id(), alicePublic.id());

        MutableHttpRequest<?> publicFeed = HttpRequest.GET("/api/v1/bookmarks");
        publicFeed.getParameters().add("visibility", Models.PUBLIC);
        PageResponse<BookmarkResponse> publicPage = bookmarks.listV1(publicFeed).getBody().orElseThrow();
        assertThat(publicPage.items())
                .extracting(BookmarkResponse::id)
                .containsExactlyInAnyOrder(alicePublic.id(), bobPublic.id());

        MutableHttpRequest<?> firstSlice = HttpRequest.GET("/api/v2/bookmarks");
        firstSlice.getParameters().add("visibility", Models.PUBLIC);
        firstSlice.getParameters().add("size", "1");
        BookmarkCursorPage first = bookmarks.listV2(firstSlice);
        assertThat(first.items()).hasSize(1);
        assertThat(first.nextCursor()).isNotBlank();

        MutableHttpRequest<?> secondSlice = HttpRequest.GET("/api/v2/bookmarks");
        secondSlice.getParameters().add("visibility", Models.PUBLIC);
        secondSlice.getParameters().add("size", "1");
        secondSlice.getParameters().add("cursor", first.nextCursor());
        BookmarkCursorPage second = bookmarks.listV2(secondSlice);
        assertThat(second.items()).hasSize(1);
        assertThat(second.items().getFirst().id()).isNotEqualTo(first.items().getFirst().id());
        assertThat(second.nextCursor()).isNull();

        assertThatThrownBy(() -> bookmarks.get(caller("mallory", "/"), alicePrivate.id().toString()))
                .isInstanceOfSatisfying(ProblemException.class,
                        problem -> assertThat(problem.status.getCode()).isEqualTo(HttpStatus.NOT_FOUND.getCode()));

        BookmarkResponse updated = bookmarks.update(
                caller("alice", "/"), alicePrivate.id().toString(),
                new BookmarkInput("https://example.test/updated", "Updated", null, List.of("updated"), Models.PRIVATE));
        assertThat(updated.title()).isEqualTo("Updated");
        assertThat(bookmarks.delete(caller("alice", "/"), alicePrivate.id().toString()).code())
                .isEqualTo(HttpStatus.NO_CONTENT.getCode());
    }

    @Test
    void moderationStateMachineAutoResolvesSiblingsAndKeepsHideRestoreExplicit() {
        BookmarkResponse bookmark = createBookmark(
                "owner", "https://example.test/reported", "Reported", List.of("moderation"), Models.PUBLIC);
        ReportResponse first = moderation.report(
                caller("reporter-a", "/"), bookmark.id().toString(), new ReportInput("spam", "first"))
                .getBody().orElseThrow();
        ReportResponse sibling = moderation.report(
                caller("reporter-b", "/"), bookmark.id().toString(), new ReportInput("offensive", "second"))
                .getBody().orElseThrow();

        assertThatThrownBy(() -> moderation.report(
                caller("reporter-a", "/"), bookmark.id().toString(), new ReportInput("other", null)))
                .isInstanceOfSatisfying(ProblemException.class,
                        problem -> assertThat(problem.status.getCode()).isEqualTo(HttpStatus.CONFLICT.getCode()));

        ReportResponse revised = moderation.updateMine(
                caller("reporter-a", "/"), first.id().toString(), new ReportInput("broken-link", "revised"));
        assertThat(revised.reason()).isEqualTo("broken-link");
        assertThat(moderation.listMine(caller("reporter-a", "/api/v1/reports")).items())
                .extracting(ReportResponse::id)
                .containsExactly(first.id());

        ReportResponse actioned = moderation.resolve(
                caller("moderator", "/", "moderator"), first.id().toString(),
                new ReportResolutionInput(Models.ACTIONED, "confirmed"));
        assertThat(actioned.status()).isEqualTo(Models.ACTIONED);
        assertThat(actioned.resolvedBy()).isEqualTo("moderator");

        MutableHttpRequest<?> siblingQuery = caller("reporter-b", "/api/v1/reports");
        siblingQuery.getParameters().add("status", Models.ACTIONED);
        assertThat(moderation.listMine(siblingQuery).items())
                .singleElement()
                .satisfies(report -> {
                    assertThat(report.id()).isEqualTo(sibling.id());
                    assertThat(report.resolutionNote()).isEqualTo("confirmed");
                });

        BookmarkResponse hidden = bookmarks.get(caller("owner", "/"), bookmark.id().toString());
        assertThat(hidden.status()).isEqualTo(Models.HIDDEN);
        assertThatThrownBy(() -> bookmarks.get(caller("visitor", "/"), bookmark.id().toString()))
                .isInstanceOf(ProblemException.class);
        assertThatThrownBy(() -> bookmarks.update(
                caller("owner", "/"), bookmark.id().toString(),
                new BookmarkInput(bookmark.url(), bookmark.title(), null, bookmark.tags(), Models.PUBLIC)))
                .isInstanceOfSatisfying(ProblemException.class,
                        problem -> assertThat(problem.status.getCode()).isEqualTo(HttpStatus.CONFLICT.getCode()));

        ReportResponse reopened = moderation.resolve(
                caller("moderator", "/", "moderator"), first.id().toString(),
                new ReportResolutionInput(Models.OPEN, "ignored"));
        assertThat(reopened.status()).isEqualTo(Models.OPEN);
        assertThat(reopened.resolvedAt()).isNull();
        assertThat(reopened.resolutionNote()).isNull();

        BookmarkResponse restored = moderation.setBookmarkStatus(
                caller("moderator", "/", "moderator"), bookmark.id().toString(),
                new BookmarkStatusInput(Models.ACTIVE, "manual restore"));
        assertThat(restored.status()).isEqualTo(Models.ACTIVE);
        assertThat(restored.visibility()).isEqualTo(Models.PUBLIC);

        assertThat(moderation.withdraw(caller("reporter-a", "/"), first.id().toString()).code())
                .isEqualTo(HttpStatus.NO_CONTENT.getCode());
        assertThat(db.scalarLong("select count(*) from reports where id = ?", first.id())).isZero();
        assertThat(db.scalarLong("select count(*) from audit_entries")).isGreaterThanOrEqualTo(5);
    }

    @Test
    void messageCrudProvidesLocalizationEtagsConflictsAndAuditEntries() throws Exception {
        MessageResponse english = messages.create(
                caller("admin", "/", "admin"), new MessageInput("ui.title", "en", "Bookmarks", "heading"))
                .getBody().orElseThrow();
        MessageResponse polish = messages.create(
                caller("admin", "/", "admin"), new MessageInput("ui.title", "pl", "Zakladki", null))
                .getBody().orElseThrow();

        assertThatThrownBy(() -> messages.create(
                caller("admin", "/", "admin"), new MessageInput("ui.title", "en", "Duplicate", null)))
                .isInstanceOfSatisfying(ProblemException.class,
                        problem -> assertThat(problem.status.getCode()).isEqualTo(HttpStatus.CONFLICT.getCode()));

        MutableHttpRequest<?> listRequest = HttpRequest.GET("/api/v1/messages");
        listRequest.getParameters().add("q", "title");
        MutableHttpResponse<?> firstList = messages.list(listRequest);
        String etag = firstList.getHeaders().get(HttpHeaders.ETAG);
        assertThat(etag).isNotBlank();
        JsonNode listBody = mapper.readTree(firstList.getBody(byte[].class).orElseThrow());
        assertThat(listBody.path("totalItems").asLong()).isEqualTo(2);

        MutableHttpRequest<?> cachedList = HttpRequest.GET("/api/v1/messages");
        cachedList.header(HttpHeaders.IF_NONE_MATCH, etag);
        assertThat(messages.list(cachedList).code()).isEqualTo(HttpStatus.NOT_MODIFIED.getCode());

        MutableHttpRequest<?> bundleRequest = HttpRequest.GET("/api/v1/messages/bundle");
        bundleRequest.header(HttpHeaders.ACCEPT_LANGUAGE, "pl-PL;q=0.9,en;q=0.5");
        MutableHttpResponse<?> bundle = messages.bundle(bundleRequest);
        assertThat(bundle.getHeaders().get(HttpHeaders.CONTENT_LANGUAGE)).isEqualTo("pl");
        assertThat(mapper.readTree(bundle.getBody(byte[].class).orElseThrow())
                .path("messages").path("ui.title").stringValue()).isEqualTo("Zakladki");
        assertThat(catalog.localize(bundleRequest, "ui.title")).isEqualTo("Zakladki");
        assertThat(catalog.localize(bundleRequest, "missing.key")).isEqualTo("missing.key");

        MessageResponse updated = messages.update(
                caller("admin", "/", "admin"), english.id().toString(),
                new MessageInput("ui.title", "en", "Saved bookmarks", null));
        assertThat(updated.text()).isEqualTo("Saved bookmarks");
        assertThat(messages.get(HttpRequest.GET("/"), english.id().toString()).code())
                .isEqualTo(HttpStatus.OK.getCode());
        assertThat(messages.delete(caller("admin", "/", "admin"), polish.id().toString()).code())
                .isEqualTo(HttpStatus.NO_CONTENT.getCode());

        assertThat(db.scalarLong("select count(*) from messages")).isEqualTo(1);
        assertThat(db.scalarLong("select count(*) from audit_entries where target_type = 'message'")).isEqualTo(4);
    }

    @Test
    void accountBlockingIsEnforcedOnTheNextRequestAndStatsStayUtcZeroFilled() throws Exception {
        accounts.recordSeen("admin");
        accounts.recordSeen("alice");
        accounts.recordSeen("bob");
        createBookmark("alice", "https://example.test/a", "A", List.of("java"), Models.PUBLIC);
        BookmarkResponse hiddenCandidate = createBookmark(
                "bob", "https://example.test/b", "B", List.of("java", "micronaut"), Models.PUBLIC);
        moderation.setBookmarkStatus(
                caller("moderator", "/", "moderator"), hiddenCandidate.id().toString(),
                new BookmarkStatusInput(Models.HIDDEN, "policy"));

        MutableHttpRequest<?> userSearch = caller("admin", "/api/v1/admin/users", "admin");
        userSearch.getParameters().add("q", "ali");
        assertThat(admin.users(userSearch).items())
                .singleElement()
                .satisfies(account -> {
                    assertThat(account.username()).isEqualTo("alice");
                    assertThat(account.bookmarkCount()).isEqualTo(1);
                });

        assertThatThrownBy(() -> admin.setUserStatus(
                caller("admin", "/", "admin"), "alice", new UserStatusInput(Models.USER_BLOCKED, " ")))
                .isInstanceOfSatisfying(ProblemException.class, problem -> {
                    assertThat(problem.status.getCode()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
                    assertThat(problem.fields).extracting(FieldViolation::messageKey)
                            .contains("validation.block.reason.required");
                });
        assertThatThrownBy(() -> admin.setUserStatus(
                caller("admin", "/", "admin"), "admin", new UserStatusInput(Models.USER_BLOCKED, "self")))
                .isInstanceOfSatisfying(ProblemException.class,
                        problem -> assertThat(problem.status.getCode()).isEqualTo(HttpStatus.CONFLICT.getCode()));

        AccountResponse blocked = admin.setUserStatus(
                caller("admin", "/", "admin"), "alice", new UserStatusInput(Models.USER_BLOCKED, "abuse"));
        assertThat(blocked.status()).isEqualTo(Models.USER_BLOCKED);
        assertThat(blocked.blockedReason()).isEqualTo("abuse");

        JwtVerifier verifier = mock(JwtVerifier.class);
        when(verifier.verify("opaque-token"))
                .thenReturn(new Identity("alice", "Alice", "alice@example.test", List.of()));
        AuthFilter filter = new AuthFilter(verifier, accounts, new ProblemHandler(catalog), security);
        MutableHttpResponse<?> blockedResponse = filter.authenticate(
                HttpRequest.GET("/api/v1/me").bearerAuth("opaque-token"));
        assertThat(blockedResponse).isNotNull();
        assertThat(blockedResponse.code()).isEqualTo(HttpStatus.FORBIDDEN.getCode());
        assertThat(blockedResponse.getBody(ProblemBody.class).orElseThrow().detail())
                .isEqualTo("error.account.blocked");

        AccountResponse active = admin.setUserStatus(
                caller("admin", "/", "admin"), "alice", new UserStatusInput(Models.USER_ACTIVE, "ignored"));
        assertThat(active.status()).isEqualTo(Models.USER_ACTIVE);
        assertThat(active.blockedReason()).isNull();

        MutableHttpResponse<?> stats = admin.stats(caller("moderator", "/api/v1/admin/stats", "moderator"));
        JsonNode statsBody = mapper.readTree(stats.getBody(byte[].class).orElseThrow());
        assertThat(statsBody.path("daily").size()).isEqualTo(30);
        assertThat(statsBody.path("totals").path("users").asLong()).isEqualTo(3);
        assertThat(statsBody.path("totals").path("bookmarks").asLong()).isEqualTo(2);
        assertThat(statsBody.path("totals").path("hiddenBookmarks").asLong()).isEqualTo(1);
        assertThat(statsBody.path("topTags").get(0).path("tag").stringValue()).isEqualTo("java");

        MutableHttpRequest<?> auditRequest = caller("admin", "/api/v1/admin/audit-log", "admin");
        auditRequest.getParameters().add("actor", "admin");
        auditRequest.getParameters().add("action", "user.blocked");
        assertThat(admin.auditLog(auditRequest).items())
                .singleElement()
                .satisfies(entry -> {
                    assertThat(entry.targetId()).isEqualTo("alice");
                    assertThat(entry.detail()).isNotNull();
                });
    }

    @Test
    void databaseTransactionsCommitRollbackAndRestoreConnectionState() throws Exception {
        String committed = db.inTx(connection -> {
            db.update(connection,
                    "insert into user_accounts (username, first_seen, last_seen, status) values (?, ?, ?, ?)",
                    "committed", Instant.EPOCH, Instant.EPOCH, Models.USER_ACTIVE);
            return "done";
        });
        assertThat(committed).isEqualTo("done");
        assertThat(db.scalarLong("select count(*) from user_accounts where username = ?", "committed")).isOne();

        assertThatThrownBy(() -> db.inTx(connection -> {
            db.update(connection,
                    "insert into user_accounts (username, first_seen, last_seen, status) values (?, ?, ?, ?)",
                    "rolled-back", Instant.EPOCH, Instant.EPOCH, Models.USER_ACTIVE);
            throw Problems.conflict("abort transaction");
        })).isInstanceOfSatisfying(ProblemException.class,
                problem -> assertThat(problem.status.getCode()).isEqualTo(HttpStatus.CONFLICT.getCode()));
        assertThat(db.scalarLong("select count(*) from user_accounts where username = ?", "rolled-back")).isZero();

        try (Connection connection = dataSource.getConnection()) {
            assertThat(connection.getAutoCommit()).isTrue();
        }
        assertThat(new MetaController(db).readyz().code()).isEqualTo(HttpStatus.OK.getCode());
        assertThatThrownBy(() -> db.one("select username from user_accounts where username = ?",
                result -> result.getString(1), "missing"))
                .isInstanceOfSatisfying(ProblemException.class,
                        problem -> assertThat(problem.status.getCode()).isEqualTo(HttpStatus.NOT_FOUND.getCode()));
    }

    private BookmarkResponse createBookmark(
            String username, String url, String title, List<String> tags, String visibility) {
        return bookmarks.create(caller(username, "/api/v1/bookmarks"),
                        new BookmarkInput(url, title, null, tags, visibility))
                .getBody().orElseThrow();
    }

    private MutableHttpRequest<?> caller(String username, String uri, String... roles) {
        MutableHttpRequest<?> request = HttpRequest.GET(uri);
        request.setAttribute(AuthFilter.IDENTITY,
                new Identity(username, username, username + "@example.test", List.of(roles)));
        return request;
    }
}
