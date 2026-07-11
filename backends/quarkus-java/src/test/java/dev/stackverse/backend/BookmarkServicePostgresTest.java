package dev.stackverse.backend;

import static dev.stackverse.backend.PostgresTestSupport.BASE_TIME;
import static dev.stackverse.backend.PostgresTestSupport.authorization;
import static dev.stackverse.backend.PostgresTestSupport.insertBookmark;
import static dev.stackverse.backend.PostgresTestSupport.request;
import static dev.stackverse.backend.PostgresTestSupport.reset;
import static dev.stackverse.backend.PostgresTestSupport.scalarLong;
import static dev.stackverse.backend.PostgresTestSupport.scalarString;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class BookmarkServicePostgresTest {
    private static final UUID ALICE_PUBLIC =
            UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID ALICE_PRIVATE =
            UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID BOB_PUBLIC = UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Inject DataSource dataSource;

    @BeforeEach
    void clearDatabase() throws SQLException {
        reset(dataSource);
    }

    @Test
    void createsUpdatesDeletesAndMasksOwnershipAtTheRealJdbcBoundary() throws SQLException {
        BookmarkService alice = service("alice");
        BookmarkInput input =
                new BookmarkInput(
                        "  https://example.com/created  ",
                        "  Created bookmark  ",
                        "notes",
                        List.of(" Java ", "java", "quarkus"),
                        null);

        Response createdResponse = alice.createBookmark(input);
        BookmarkResponse created =
                assertInstanceOf(BookmarkResponse.class, createdResponse.getEntity());

        assertEquals(201, createdResponse.getStatus());
        assertEquals("/api/v1/bookmarks/" + created.id(), createdResponse.getLocation().toString());
        assertEquals("https://example.com/created", created.url());
        assertEquals("Created bookmark", created.title());
        assertEquals(List.of("java", "quarkus"), created.tags());
        assertEquals("private", created.visibility());
        assertEquals(
                "alice",
                scalarString(dataSource, "select owner from bookmarks where id = ?", created.id()));

        assertEquals(200, alice.getBookmark(created.id().toString()).getStatus());
        StackverseProblem masked =
                assertThrows(
                        StackverseProblem.class,
                        () -> service("bob").getBookmark(created.id().toString()));
        assertEquals(404, masked.status);

        StackverseProblem nonOwnerUpdate =
                assertThrows(
                        StackverseProblem.class,
                        () -> service("bob").updateBookmark(created.id().toString(), input));
        assertEquals(404, nonOwnerUpdate.status);

        PostgresTestSupport.execute(
                dataSource, "update bookmarks set status = 'hidden' where id = ?", created.id());
        StackverseProblem hiddenPublish =
                assertThrows(
                        StackverseProblem.class,
                        () ->
                                alice.updateBookmark(
                                        created.id().toString(),
                                        new BookmarkInput(
                                                created.url(),
                                                "Republished",
                                                null,
                                                List.of(),
                                                "public")));
        assertEquals(409, hiddenPublish.status);
        assertEquals("error.bookmark.hidden-publish", hiddenPublish.detailKey);

        Response hiddenEdit =
                alice.updateBookmark(
                        created.id().toString(),
                        new BookmarkInput(
                                created.url(), "Edited while hidden", null, List.of(), "private"));
        assertEquals(200, hiddenEdit.getStatus());
        assertEquals(
                "hidden",
                assertInstanceOf(BookmarkResponse.class, hiddenEdit.getEntity()).status());

        StackverseProblem nonOwnerDelete =
                assertThrows(
                        StackverseProblem.class,
                        () -> service("bob").deleteBookmark(created.id().toString()));
        assertEquals(404, nonOwnerDelete.status);
        assertEquals(204, alice.deleteBookmark(created.id().toString()).getStatus());
        assertEquals(
                0,
                scalarLong(
                        dataSource, "select count(*) from bookmarks where id = ?", created.id()));
    }

    @Test
    void listsOwnedAndPublicBookmarksWithFiltersTagsAndDeprecationHeaders() throws SQLException {
        insertBookmark(
                dataSource,
                ALICE_PUBLIC,
                "alice",
                "public",
                "active",
                BASE_TIME,
                "100%_Java",
                List.of("java"));
        insertBookmark(
                dataSource,
                ALICE_PRIVATE,
                "alice",
                "private",
                "active",
                BASE_TIME.plusSeconds(10),
                "Private database",
                List.of("java", "db"));
        insertBookmark(
                dataSource,
                BOB_PUBLIC,
                "bob",
                "public",
                "active",
                BASE_TIME.plusSeconds(20),
                "Bob database",
                List.of("java", "db"));
        insertBookmark(
                dataSource,
                UUID.fromString("44444444-4444-4444-4444-444444444444"),
                "bob",
                "public",
                "hidden",
                BASE_TIME.plusSeconds(30),
                "Hidden",
                List.of("java", "db"));

        Response ownResponse = service("alice").listBookmarksV1(request());
        PageResponse<?> own = assertInstanceOf(PageResponse.class, ownResponse.getEntity());
        assertEquals(List.of(ALICE_PRIVATE, ALICE_PUBLIC), bookmarkIds(own.items()));
        assertEquals(2, own.totalItems());
        assertEquals("@1782864000", ownResponse.getHeaderString("Deprecation"));
        assertNotNull(ownResponse.getHeaderString("Sunset"));
        assertTrue(ownResponse.getHeaderString("Link").contains("/api/v2/bookmarks"));

        Response publicResponse =
                service(null)
                        .listBookmarksV1(
                                request(
                                        Map.of(
                                                "visibility", List.of("public"),
                                                "tag", List.of("java", "db"))));
        PageResponse<?> publicPage =
                assertInstanceOf(PageResponse.class, publicResponse.getEntity());
        assertEquals(List.of(BOB_PUBLIC), bookmarkIds(publicPage.items()));

        Response escapedSearch =
                service(null)
                        .listBookmarksV1(
                                request(
                                        Map.of(
                                                "visibility", List.of("public"),
                                                "q", List.of("100%_"))));
        assertEquals(
                List.of(ALICE_PUBLIC),
                bookmarkIds(
                        assertInstanceOf(PageResponse.class, escapedSearch.getEntity()).items()));

        TagsResponse tags =
                assertInstanceOf(TagsResponse.class, service("alice").listTags().getEntity());
        assertEquals(List.of(new TagCount("java", 2), new TagCount("db", 1)), tags.tags());
    }

    @Test
    void cursorPaginationStaysStableAcrossConcurrentInsertsAndRejectsBadQueries()
            throws SQLException {
        UUID newest = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff3");
        UUID middle = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff2");
        UUID oldest = UUID.fromString("ffffffff-ffff-ffff-ffff-fffffffffff1");
        insertBookmark(
                dataSource,
                oldest,
                "alice",
                "public",
                "active",
                BASE_TIME,
                "Oldest",
                List.of("java"));
        insertBookmark(
                dataSource,
                middle,
                "alice",
                "public",
                "active",
                BASE_TIME.plusSeconds(10),
                "Middle",
                List.of("java"));
        insertBookmark(
                dataSource,
                newest,
                "alice",
                "public",
                "active",
                BASE_TIME.plusSeconds(20),
                "Newest",
                List.of("java"));

        BookmarkService anonymous = service(null);
        CursorPage<?> first =
                assertInstanceOf(
                        CursorPage.class,
                        anonymous
                                .listBookmarksV2(
                                        request(
                                                Map.of(
                                                        "visibility", List.of("public"),
                                                        "size", List.of("2"))))
                                .getEntity());
        assertEquals(List.of(newest, middle), bookmarkIds(first.items()));
        assertNotNull(first.nextCursor());

        UUID concurrent = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        insertBookmark(
                dataSource,
                concurrent,
                "bob",
                "public",
                "active",
                BASE_TIME.plusSeconds(30),
                "Concurrent",
                List.of("java"));
        CursorPage<?> second =
                assertInstanceOf(
                        CursorPage.class,
                        anonymous
                                .listBookmarksV2(
                                        request(
                                                Map.of(
                                                        "visibility", List.of("public"),
                                                        "size", List.of("2"),
                                                        "cursor", List.of(first.nextCursor()))))
                                .getEntity());
        assertEquals(List.of(oldest), bookmarkIds(second.items()));
        assertNull(second.nextCursor());
        assertFalse(bookmarkIds(second.items()).contains(concurrent));

        StackverseProblem malformed =
                assertThrows(
                        StackverseProblem.class,
                        () ->
                                anonymous.listBookmarksV2(
                                        request(
                                                Map.of(
                                                        "visibility", List.of("public"),
                                                        "cursor", List.of("not-a-cursor")))));
        assertEquals(400, malformed.status);

        StackverseProblem invalidTag =
                assertThrows(
                        StackverseProblem.class,
                        () ->
                                anonymous.listBookmarksV2(
                                        request(
                                                Map.of(
                                                        "visibility", List.of("public"),
                                                        "tag", List.of("not valid")))));
        assertEquals(400, invalidTag.status);
        assertEquals(
                List.of(new FieldViolation("tag", "validation.tag.invalid")), invalidTag.fields);

        StackverseProblem anonymousPrivate =
                assertThrows(StackverseProblem.class, () -> anonymous.listBookmarksV1(request()));
        assertEquals(401, anonymousPrivate.status);
    }

    private BookmarkService service(String username) {
        return new BookmarkService(
                new DatabaseOperations(dataSource),
                authorization(username),
                new RequestParameters());
    }

    private static List<UUID> bookmarkIds(List<?> items) {
        return items.stream().map(BookmarkResponse.class::cast).map(BookmarkResponse::id).toList();
    }
}
