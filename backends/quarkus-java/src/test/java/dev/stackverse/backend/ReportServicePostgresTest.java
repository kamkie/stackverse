package dev.stackverse.backend;

import static dev.stackverse.backend.PostgresTestSupport.BASE_TIME;
import static dev.stackverse.backend.PostgresTestSupport.authorization;
import static dev.stackverse.backend.PostgresTestSupport.insertBookmark;
import static dev.stackverse.backend.PostgresTestSupport.insertReport;
import static dev.stackverse.backend.PostgresTestSupport.request;
import static dev.stackverse.backend.PostgresTestSupport.reset;
import static dev.stackverse.backend.PostgresTestSupport.scalarLong;
import static dev.stackverse.backend.PostgresTestSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(PostgresTestProfile.class)
class ReportServicePostgresTest {
    private static final UUID PUBLIC_BOOKMARK =
            UUID.fromString("bbbbbbbb-1111-1111-1111-111111111111");
    private static final UUID PRIVATE_BOOKMARK =
            UUID.fromString("bbbbbbbb-2222-2222-2222-222222222222");
    private static final UUID HIDDEN_BOOKMARK =
            UUID.fromString("bbbbbbbb-3333-3333-3333-333333333333");
    private static final UUID PRIMARY_REPORT =
            UUID.fromString("cccccccc-1111-1111-1111-111111111111");
    private static final UUID SIBLING_REPORT =
            UUID.fromString("cccccccc-2222-2222-2222-222222222222");
    private static final UUID DISMISSED_REPORT =
            UUID.fromString("cccccccc-3333-3333-3333-333333333333");

    @Inject DataSource dataSource;
    @Inject ObjectMapper mapper;

    @BeforeEach
    void clearDatabase() throws SQLException {
        reset(dataSource);
    }

    @Test
    void reporterCanCreateListEditWithdrawAndRefileOnlyVisibleOpenReports() throws SQLException {
        seedBookmarks();
        ReportService alice = service("alice");

        Response createdResponse =
                alice.reportBookmark(
                        PUBLIC_BOOKMARK.toString(), new ReportInput("spam", "first report"));
        ReportResponse created =
                assertInstanceOf(ReportResponse.class, createdResponse.getEntity());
        assertEquals(201, createdResponse.getStatus());
        assertEquals("alice", created.reporter());
        assertEquals("open", created.status());

        StackverseProblem duplicate =
                assertThrows(
                        StackverseProblem.class,
                        () ->
                                alice.reportBookmark(
                                        PUBLIC_BOOKMARK.toString(),
                                        new ReportInput("offensive", null)));
        assertEquals(409, duplicate.status);

        StackverseProblem privateMasked =
                assertThrows(
                        StackverseProblem.class,
                        () ->
                                alice.reportBookmark(
                                        PRIVATE_BOOKMARK.toString(),
                                        new ReportInput("spam", null)));
        assertEquals(404, privateMasked.status);
        StackverseProblem hiddenMasked =
                assertThrows(
                        StackverseProblem.class,
                        () ->
                                alice.reportBookmark(
                                        HIDDEN_BOOKMARK.toString(), new ReportInput("spam", null)));
        assertEquals(404, hiddenMasked.status);

        PageResponse<?> ownPage =
                assertInstanceOf(
                        PageResponse.class,
                        alice.listMyReports(request(Map.of("status", List.of("open"))))
                                .getEntity());
        assertEquals(1, ownPage.totalItems());
        assertEquals(created.id(), reportResponses(ownPage).get(0).id());

        StackverseProblem otherReporter =
                assertThrows(
                        StackverseProblem.class,
                        () ->
                                service("bob")
                                        .updateMyReport(
                                                created.id().toString(),
                                                new ReportInput("other", "masked")));
        assertEquals(404, otherReporter.status);

        ReportResponse updated =
                assertInstanceOf(
                        ReportResponse.class,
                        alice.updateMyReport(
                                        created.id().toString(),
                                        new ReportInput("broken-link", "updated"))
                                .getEntity());
        assertEquals("broken-link", updated.reason());
        assertEquals("updated", updated.comment());

        assertEquals(204, alice.withdrawReport(created.id().toString()).getStatus());
        assertEquals(
                0,
                scalarLong(dataSource, "select count(*) from reports where id = ?", created.id()));
        assertEquals(
                201,
                alice.reportBookmark(
                                PUBLIC_BOOKMARK.toString(), new ReportInput("other", "refiled"))
                        .getStatus());

        StackverseProblem invalidStatus =
                assertThrows(
                        StackverseProblem.class,
                        () -> alice.listMyReports(request(Map.of("status", List.of("invalid")))));
        assertEquals(400, invalidStatus.status);
    }

    @Test
    void actioningAutoResolvesSiblingsHidesOnceAndSupportsRevisionAndReopening()
            throws SQLException {
        seedBookmarks();
        insertReport(
                dataSource,
                PRIMARY_REPORT,
                PUBLIC_BOOKMARK,
                "alice",
                "spam",
                "primary",
                "open",
                null,
                null,
                null,
                BASE_TIME);
        insertReport(
                dataSource,
                SIBLING_REPORT,
                PUBLIC_BOOKMARK,
                "bob",
                "offensive",
                "sibling",
                "open",
                null,
                null,
                null,
                BASE_TIME.plusSeconds(1));
        insertReport(
                dataSource,
                DISMISSED_REPORT,
                PUBLIC_BOOKMARK,
                "charlie",
                "other",
                null,
                "dismissed",
                "older-moderator",
                BASE_TIME.plusSeconds(2),
                "kept",
                BASE_TIME.minusSeconds(1));
        ReportService moderator = service("moderator", "moderator");

        ReportResponse actioned =
                assertInstanceOf(
                        ReportResponse.class,
                        moderator
                                .resolveReport(
                                        PRIMARY_REPORT.toString(),
                                        new ResolutionInput("actioned", "confirmed"))
                                .getEntity());
        assertEquals("actioned", actioned.status());
        assertEquals("moderator", actioned.resolvedBy());
        assertEquals(
                "hidden",
                scalarString(
                        dataSource, "select status from bookmarks where id = ?", PUBLIC_BOOKMARK));
        assertEquals(
                "actioned",
                scalarString(
                        dataSource, "select status from reports where id = ?", SIBLING_REPORT));
        assertEquals(
                "dismissed",
                scalarString(
                        dataSource, "select status from reports where id = ?", DISMISSED_REPORT));
        assertEquals(
                2,
                scalarLong(
                        dataSource,
                        "select count(*) from audit_entries where action = 'report.resolved'"));
        assertEquals(
                1,
                scalarLong(
                        dataSource,
                        "select count(*) from audit_entries where action = 'bookmark.status-changed'"));

        ReportResponse revised =
                assertInstanceOf(
                        ReportResponse.class,
                        moderator
                                .resolveReport(
                                        PRIMARY_REPORT.toString(),
                                        new ResolutionInput("dismissed", "reconsidered"))
                                .getEntity());
        assertEquals("dismissed", revised.status());
        assertEquals(
                "hidden",
                scalarString(
                        dataSource, "select status from bookmarks where id = ?", PUBLIC_BOOKMARK));

        ReportResponse reopened =
                assertInstanceOf(
                        ReportResponse.class,
                        moderator
                                .resolveReport(
                                        PRIMARY_REPORT.toString(),
                                        new ResolutionInput("open", "ignored note"))
                                .getEntity());
        assertEquals("open", reopened.status());
        assertNull(reopened.resolvedBy());
        assertNull(reopened.resolvedAt());
        assertNull(reopened.resolutionNote());
        assertEquals(
                1,
                scalarLong(
                        dataSource,
                        "select count(*) from audit_entries where action = 'report.reopened'"));

        insertReport(
                dataSource,
                UUID.fromString("cccccccc-4444-4444-4444-444444444444"),
                PUBLIC_BOOKMARK,
                "alice",
                "other",
                null,
                "dismissed",
                "moderator",
                BASE_TIME,
                null,
                BASE_TIME.minusSeconds(2));
        StackverseProblem duplicateReopen =
                assertThrows(
                        StackverseProblem.class,
                        () ->
                                moderator.resolveReport(
                                        "cccccccc-4444-4444-4444-444444444444",
                                        new ResolutionInput("open", null)));
        assertEquals(409, duplicateReopen.status);

        PageResponse<?> queue =
                assertInstanceOf(
                        PageResponse.class, moderator.listReportQueue(request()).getEntity());
        assertEquals(
                List.of(PRIMARY_REPORT),
                reportResponses(queue).stream().map(ReportResponse::id).toList());
    }

    @Test
    void explicitBookmarkModerationRestoresWithoutChangingVisibilityAndRequiresRole()
            throws SQLException {
        seedBookmarks();
        ReportService moderator = service("moderator", "moderator");

        Response hidden =
                moderator.setBookmarkStatus(
                        PUBLIC_BOOKMARK.toString(),
                        new BookmarkStatusInput("hidden", "manual hide"));
        BookmarkResponse hiddenBody = assertInstanceOf(BookmarkResponse.class, hidden.getEntity());
        assertEquals("hidden", hiddenBody.status());
        assertEquals("public", hiddenBody.visibility());

        BookmarkResponse restored =
                assertInstanceOf(
                        BookmarkResponse.class,
                        moderator
                                .setBookmarkStatus(
                                        PUBLIC_BOOKMARK.toString(),
                                        new BookmarkStatusInput("active", "restored"))
                                .getEntity());
        assertEquals("active", restored.status());
        assertEquals("public", restored.visibility());
        assertEquals(
                2,
                scalarLong(
                        dataSource,
                        "select count(*) from audit_entries where action = 'bookmark.status-changed'"));

        StackverseProblem reader =
                assertThrows(
                        StackverseProblem.class,
                        () ->
                                service("reader")
                                        .setBookmarkStatus(
                                                PUBLIC_BOOKMARK.toString(),
                                                new BookmarkStatusInput("hidden", null)));
        assertEquals(403, reader.status);

        StackverseProblem invalidQueue =
                assertThrows(
                        StackverseProblem.class,
                        () ->
                                moderator.listReportQueue(
                                        request(Map.of("status", List.of("invalid")))));
        assertEquals(400, invalidQueue.status);
        assertTrue(invalidQueue.detail.contains("open, dismissed, actioned"));
    }

    private void seedBookmarks() throws SQLException {
        insertBookmark(
                dataSource,
                PUBLIC_BOOKMARK,
                "owner",
                "public",
                "active",
                BASE_TIME,
                "Public",
                List.of("java"));
        insertBookmark(
                dataSource,
                PRIVATE_BOOKMARK,
                "owner",
                "private",
                "active",
                BASE_TIME,
                "Private",
                List.of());
        insertBookmark(
                dataSource,
                HIDDEN_BOOKMARK,
                "owner",
                "public",
                "hidden",
                BASE_TIME,
                "Hidden",
                List.of());
    }

    private ReportService service(String username, String... roles) {
        return new ReportService(
                new DatabaseOperations(dataSource),
                authorization(username, roles),
                new RequestParameters(),
                new AuditTrail(mapper));
    }

    private static List<ReportResponse> reportResponses(PageResponse<?> page) {
        return page.items().stream().map(ReportResponse.class::cast).toList();
    }
}
