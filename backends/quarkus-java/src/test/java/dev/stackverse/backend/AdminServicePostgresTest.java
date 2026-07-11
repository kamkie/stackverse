package dev.stackverse.backend;

import static dev.stackverse.backend.PostgresTestSupport.BASE_TIME;
import static dev.stackverse.backend.PostgresTestSupport.authorization;
import static dev.stackverse.backend.PostgresTestSupport.insertBookmark;
import static dev.stackverse.backend.PostgresTestSupport.insertReport;
import static dev.stackverse.backend.PostgresTestSupport.insertUser;
import static dev.stackverse.backend.PostgresTestSupport.request;
import static dev.stackverse.backend.PostgresTestSupport.reset;
import static dev.stackverse.backend.PostgresTestSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(PostgresTestProfile.class)
class AdminServicePostgresTest {
    private static final UUID ALICE_BOOKMARK =
            UUID.fromString("dddddddd-1111-1111-1111-111111111111");
    private static final UUID BOB_BOOKMARK =
            UUID.fromString("dddddddd-2222-2222-2222-222222222222");

    @Inject DataSource dataSource;
    @Inject ObjectMapper mapper;

    @BeforeEach
    void clearDatabase() throws SQLException {
        reset(dataSource);
    }

    @Test
    void listsGetsBlocksAndUnblocksAccountsWithOwnershipCountsAndAudits() throws SQLException {
        seedUsersAndBookmarks();
        AdminService admin = service("admin", "admin", "moderator");

        PageResponse<?> filtered =
                assertInstanceOf(
                        PageResponse.class,
                        admin.listUsers(
                                        request(
                                                Map.of(
                                                        "q", List.of("ALI"),
                                                        "status", List.of("active"),
                                                        "page", List.of("0"),
                                                        "size", List.of("5"))))
                                .getEntity());
        assertEquals(1, filtered.totalItems());
        UserAccountResponse alice =
                assertInstanceOf(UserAccountResponse.class, filtered.items().get(0));
        assertEquals("alice", alice.username());
        assertEquals(1, alice.bookmarkCount());

        UserAccountResponse bob =
                assertInstanceOf(UserAccountResponse.class, admin.getUser("bob").getEntity());
        assertEquals("blocked", bob.status());
        assertEquals("prior reason", bob.blockedReason());
        assertEquals(1, bob.bookmarkCount());

        UserAccountResponse blocked =
                assertInstanceOf(
                        UserAccountResponse.class,
                        admin.setUserStatus(
                                        "alice", new UserStatusInput("blocked", "policy violation"))
                                .getEntity());
        assertEquals("blocked", blocked.status());
        assertEquals("policy violation", blocked.blockedReason());
        assertEquals(
                "user.blocked",
                scalarString(
                        dataSource,
                        "select action from audit_entries where target_id = 'alice' order by created_at desc limit 1"));
        assertEquals(
                "policy violation",
                scalarString(
                        dataSource,
                        "select detail->>'reason' from audit_entries where target_id = 'alice'"));

        UserAccountResponse active =
                assertInstanceOf(
                        UserAccountResponse.class,
                        admin.setUserStatus("alice", new UserStatusInput("active", "ignored"))
                                .getEntity());
        assertEquals("active", active.status());
        assertNull(active.blockedReason());
        assertEquals(
                "user.unblocked",
                scalarString(
                        dataSource,
                        "select action from audit_entries where target_id = 'alice' order by created_at desc limit 1"));

        StackverseProblem selfBlock =
                assertThrows(
                        StackverseProblem.class,
                        () -> admin.setUserStatus("admin", new UserStatusInput("blocked", "self")));
        assertEquals(409, selfBlock.status);
        StackverseProblem missing =
                assertThrows(
                        StackverseProblem.class,
                        () -> admin.setUserStatus("missing", new UserStatusInput("active", null)));
        assertEquals(404, missing.status);
        StackverseProblem invalid =
                assertThrows(
                        StackverseProblem.class,
                        () -> admin.setUserStatus("alice", new UserStatusInput("unknown", null)));
        assertEquals(400, invalid.status);
        StackverseProblem reader =
                assertThrows(StackverseProblem.class, () -> service("reader").getUser("alice"));
        assertEquals(403, reader.status);
    }

    @Test
    void auditLogFiltersTimeAndTargetsAndToleratesLegacyInvalidDetailJson() throws SQLException {
        seedUsersAndBookmarks();
        AdminService admin = service("admin", "admin", "moderator");
        admin.setUserStatus("alice", new UserStatusInput("blocked", "audit detail"));
        PostgresTestSupport.execute(
                dataSource,
                "insert into audit_entries"
                        + " (id, actor, action, target_type, target_id, detail, created_at)"
                        + " values (?, ?, ?, ?, ?, cast(? as jsonb), ?)",
                UUID.fromString("eeeeeeee-1111-1111-1111-111111111111"),
                "other-admin",
                "message.created",
                "message",
                "message-1",
                "{\"key\":\"ui.title\"}",
                BASE_TIME);
        PostgresTestSupport.execute(
                dataSource,
                "insert into audit_entries"
                        + " (id, actor, action, target_type, target_id, detail, created_at)"
                        + " values (?, ?, ?, ?, ?, null, ?)",
                UUID.fromString("eeeeeeee-2222-2222-2222-222222222222"),
                "other-admin",
                "message.deleted",
                "message",
                "message-2",
                BASE_TIME.plusSeconds(1));

        PageResponse<?> filtered =
                assertInstanceOf(
                        PageResponse.class,
                        admin.auditLog(
                                        request(
                                                Map.of(
                                                        "actor", List.of("other-admin"),
                                                        "action", List.of("message.created"),
                                                        "targetType", List.of("message"),
                                                        "targetId", List.of("message-1"),
                                                        "from",
                                                                List.of(
                                                                        BASE_TIME
                                                                                .minusSeconds(1)
                                                                                .toString()),
                                                        "to",
                                                                List.of(
                                                                        BASE_TIME
                                                                                .plusSeconds(1)
                                                                                .toString()))))
                                .getEntity());
        assertEquals(1, filtered.totalItems());
        AuditResponse audit = assertInstanceOf(AuditResponse.class, filtered.items().get(0));
        assertEquals("ui.title", audit.detail().get("key"));

        PageResponse<?> nullDetail =
                assertInstanceOf(
                        PageResponse.class,
                        admin.auditLog(request(Map.of("targetId", List.of("message-2"))))
                                .getEntity());
        assertNull(assertInstanceOf(AuditResponse.class, nullDetail.items().get(0)).detail());

        StackverseProblem invalidTime =
                assertThrows(
                        StackverseProblem.class,
                        () -> admin.auditLog(request(Map.of("from", List.of("not-a-timestamp")))));
        assertEquals(400, invalidTime.status);
    }

    @Test
    void statsZeroFillThirtyUtcDaysAggregateTopTagsAndRevalidateByEtag() throws Exception {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        Instant todayStart = today.atStartOfDay(ZoneOffset.UTC).toInstant();
        insertUser(dataSource, "alice", "active", null, todayStart.plusSeconds(10));
        insertUser(dataSource, "bob", "blocked", "reason", todayStart.minusSeconds(10));
        insertBookmark(
                dataSource,
                ALICE_BOOKMARK,
                "alice",
                "public",
                "active",
                todayStart.plusSeconds(20),
                "Today",
                List.of("java", "quarkus"));
        insertBookmark(
                dataSource,
                BOB_BOOKMARK,
                "bob",
                "public",
                "hidden",
                todayStart.minusSeconds(86_400),
                "Yesterday",
                List.of("java"));
        insertReport(
                dataSource,
                UUID.fromString("ffffffff-1111-1111-1111-111111111111"),
                ALICE_BOOKMARK,
                "bob",
                "spam",
                null,
                "open",
                null,
                null,
                null,
                todayStart.plusSeconds(30));

        AdminService moderator = service("moderator", "moderator");
        Response stats = moderator.stats(request());
        JsonNode body = jsonBody(stats);

        assertEquals(200, stats.getStatus());
        assertEquals(2, body.at("/totals/users").asInt());
        assertEquals(2, body.at("/totals/bookmarks").asInt());
        assertEquals(2, body.at("/totals/publicBookmarks").asInt());
        assertEquals(1, body.at("/totals/hiddenBookmarks").asInt());
        assertEquals(1, body.at("/totals/openReports").asInt());
        assertEquals(30, body.get("daily").size());
        assertEquals(today.toString(), body.at("/daily/29/date").asText());
        assertEquals(1, body.at("/daily/29/bookmarksCreated").asInt());
        assertEquals(1, body.at("/daily/29/activeUsers").asInt());
        assertEquals("java", body.at("/topTags/0/tag").asText());
        assertEquals(2, body.at("/topTags/0/count").asInt());
        assertEquals("quarkus", body.at("/topTags/1/tag").asText());

        String etag = stats.getHeaderString("ETag");
        Response notModified =
                moderator.stats(request(Map.of(), Map.of(HttpHeaders.IF_NONE_MATCH, etag)));
        assertEquals(304, notModified.getStatus());
        assertEquals("no-cache", notModified.getHeaderString("Cache-Control"));

        StackverseProblem reader =
                assertThrows(StackverseProblem.class, () -> service("reader").stats(request()));
        assertEquals(403, reader.status);
    }

    private void seedUsersAndBookmarks() throws SQLException {
        insertUser(dataSource, "admin", "active", null, BASE_TIME.plusSeconds(20));
        insertUser(dataSource, "alice", "active", null, BASE_TIME.plusSeconds(10));
        insertUser(dataSource, "bob", "blocked", "prior reason", BASE_TIME);
        insertBookmark(
                dataSource,
                ALICE_BOOKMARK,
                "alice",
                "private",
                "active",
                BASE_TIME,
                "Alice bookmark",
                List.of("java"));
        insertBookmark(
                dataSource,
                BOB_BOOKMARK,
                "bob",
                "public",
                "active",
                BASE_TIME,
                "Bob bookmark",
                List.of("db"));
    }

    private AdminService service(String username, String... roles) {
        return new AdminService(
                new DatabaseOperations(dataSource),
                authorization(username, roles),
                new RequestParameters(),
                new HttpResponses(mapper),
                new AuditTrail(mapper),
                mapper);
    }

    private JsonNode jsonBody(Response response) throws Exception {
        return mapper.readTree(assertInstanceOf(String.class, response.getEntity()));
    }
}
