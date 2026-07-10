package dev.stackverse.backend;

import static dev.stackverse.backend.HttpResponses.pageResponse;
import static dev.stackverse.backend.HttpResponses.v1BookmarksDeprecationHeaders;
import static dev.stackverse.backend.PersistenceSupport.execute;
import static dev.stackverse.backend.PersistenceSupport.instant;
import static dev.stackverse.backend.PersistenceSupport.now;
import static dev.stackverse.backend.PersistenceSupport.params;
import static dev.stackverse.backend.PersistenceSupport.query;
import static dev.stackverse.backend.PersistenceSupport.queryOne;
import static dev.stackverse.backend.PersistenceSupport.scalarLong;
import static dev.stackverse.backend.PersistenceSupport.textArray;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class BookmarkService {
    private static final Pattern TAG_PATTERN = Pattern.compile("^[a-z0-9-]{1,30}$");

    private final DatabaseOperations database;
    private final Authorization authorization;
    private final RequestParameters requestParameters;

    BookmarkService(
            DatabaseOperations database,
            Authorization authorization,
            RequestParameters requestParameters) {
        this.database = database;
        this.authorization = authorization;
        this.requestParameters = requestParameters;
    }

    public Response listBookmarksV1(RequestContext request) {
        int page = requestParameters.pagingPage(request);
        int size = requestParameters.pageSize(request);
        SqlWhere query = parseBookmarkListQuery(request);
        Page<Bookmark> result =
                database.withConnection(
                        connection -> {
                            long total =
                                    scalarLong(
                                            connection,
                                            "select count(*) from bookmarks " + query.sql(),
                                            query.params());
                            List<Object> params = new ArrayList<>(query.params());
                            params.add(size);
                            params.add(requestParameters.offset(page, size));
                            List<Bookmark> items =
                                    query(
                                            connection,
                                            "select * from bookmarks "
                                                    + query.sql()
                                                    + " order by created_at desc, id desc limit ? offset ?",
                                            params,
                                            BookmarkService::bookmark);
                            return new Page<>(items, page, size, total);
                        });
        List<BookmarkResponse> items =
                result.items().stream().map(BookmarkService::bookmarkResponse).toList();
        return v1BookmarksDeprecationHeaders(
                Response.ok(pageResponse(items, result.page(), result.size(), result.totalItems()))
                        .build());
    }

    public Response listBookmarksV2(RequestContext request) {
        int size = requestParameters.pageSize(request);
        SqlWhere query = parseBookmarkListQuery(request);
        Cursor cursor =
                Optional.ofNullable(requestParameters.singleParam(request, "cursor"))
                        .filter(value -> !value.isBlank())
                        .map(Cursor::decode)
                        .orElse(null);
        List<Bookmark> fetched =
                database.withConnection(
                        connection -> {
                            String where = query.sql();
                            List<Object> params = new ArrayList<>(query.params());
                            if (cursor != null) {
                                where += " and (created_at, id) < (?, ?)";
                                params.add(cursor.createdAt());
                                params.add(cursor.id());
                            }
                            params.add(size + 1);
                            return query(
                                    connection,
                                    "select * from bookmarks "
                                            + where
                                            + " order by created_at desc, id desc limit ?",
                                    params,
                                    BookmarkService::bookmark);
                        });
        List<Bookmark> items = fetched.size() > size ? fetched.subList(0, size) : fetched;
        String nextCursor = null;
        if (fetched.size() > size && !items.isEmpty()) {
            Bookmark last = items.get(items.size() - 1);
            nextCursor = new Cursor(last.createdAt(), last.id()).encode();
        }
        return Response.ok(
                        new CursorPage<>(
                                items.stream().map(BookmarkService::bookmarkResponse).toList(),
                                nextCursor))
                .build();
    }

    public Response createBookmark(BookmarkInput input) {
        Caller caller = authorization.requireCaller();
        requireBookmarkVisibility(input.visibility());
        Bookmark created =
                database.withConnection(
                        connection -> {
                            UUID id = UUID.randomUUID();
                            Instant now = now();
                            return queryOne(
                                            connection,
                                            "insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)"
                                                    + " values (?, ?, ?, ?, ?, ?::text[], ?, 'active', ?, ?) returning *",
                                            params(
                                                    id,
                                                    caller.username(),
                                                    input.url(),
                                                    input.title(),
                                                    input.notes(),
                                                    input.tags(),
                                                    input.visibility(),
                                                    now,
                                                    now),
                                            BookmarkService::bookmark)
                                    .orElseThrow();
                        });
        return Response.created(URI.create("/api/v1/bookmarks/" + created.id()))
                .entity(bookmarkResponse(created))
                .build();
    }

    public Response getBookmark(String rawId) {
        UUID id = requestParameters.parseUuid(rawId);
        Caller caller = authorization.currentCaller();
        Bookmark bookmark =
                database.withConnection(connection -> findBookmark(connection, id))
                        .orElseThrow(StackverseProblem::notFound);
        if (!bookmark.visibleTo(caller == null ? null : caller.username())) {
            throw StackverseProblem.notFound();
        }
        return Response.ok(bookmarkResponse(bookmark)).build();
    }

    public Response updateBookmark(String rawId, BookmarkInput input) {
        Caller caller = authorization.requireCaller();
        UUID id = requestParameters.parseUuid(rawId);
        requireBookmarkVisibility(input.visibility());
        Bookmark updated =
                database.inTransaction(
                        connection -> {
                            Bookmark bookmark =
                                    queryOne(
                                                    connection,
                                                    "select * from bookmarks where id = ? for update",
                                                    List.of(id),
                                                    BookmarkService::bookmark)
                                            .orElseThrow(StackverseProblem::notFound);
                            if (!bookmark.owner().equals(caller.username())) {
                                throw StackverseProblem.notFound();
                            }
                            if ("hidden".equals(bookmark.status())
                                    && "public".equals(input.visibility())) {
                                throw StackverseProblem.conflictKey(
                                        "error.bookmark.hidden-publish");
                            }
                            return queryOne(
                                            connection,
                                            "update bookmarks set url = ?, title = ?, notes = ?, tags = ?::text[], visibility = ?, updated_at = ?"
                                                    + " where id = ? returning *",
                                            params(
                                                    input.url(),
                                                    input.title(),
                                                    input.notes(),
                                                    input.tags(),
                                                    input.visibility(),
                                                    now(),
                                                    id),
                                            BookmarkService::bookmark)
                                    .orElseThrow();
                        });
        return Response.ok(bookmarkResponse(updated)).build();
    }

    private static void requireBookmarkVisibility(String visibility) {
        if (!Set.of("private", "public").contains(visibility)) {
            throw StackverseProblem.badRequest("visibility must be one of: private, public");
        }
    }

    public Response deleteBookmark(String rawId) {
        Caller caller = authorization.requireCaller();
        UUID id = requestParameters.parseUuid(rawId);
        database.withConnection(
                connection -> {
                    Bookmark bookmark =
                            findBookmark(connection, id).orElseThrow(StackverseProblem::notFound);
                    if (!bookmark.owner().equals(caller.username())) {
                        throw StackverseProblem.notFound();
                    }
                    execute(connection, "delete from bookmarks where id = ?", List.of(id));
                    return null;
                });
        return Response.noContent().build();
    }

    public Response listTags() {
        Caller caller = authorization.requireCaller();
        List<TagCount> tags =
                database.withConnection(
                        connection ->
                                query(
                                        connection,
                                        "select tag, count(*) as count from bookmarks, unnest(tags) as tag"
                                                + " where owner = ? group by tag order by count desc, tag",
                                        List.of(caller.username()),
                                        rs ->
                                                new TagCount(
                                                        rs.getString("tag"), rs.getLong("count"))));
        return Response.ok(new TagsResponse(tags)).build();
    }

    private SqlWhere parseBookmarkListQuery(RequestContext request) {
        String q = requestParameters.singleParam(request, "q");
        requestParameters.maxLength(q, 200, "q");
        String visibility = requestParameters.singleParam(request, "visibility");
        if (visibility != null && !Set.of("private", "public").contains(visibility)) {
            throw StackverseProblem.badRequest("visibility must be one of: private, public");
        }
        List<String> tags =
                normalizeQueryTags(
                        requestParameters.queryParams(request).getOrDefault("tag", List.of()));
        SqlWhere where = new SqlWhere();
        if (!"public".equals(visibility)) {
            Caller caller = authorization.currentCaller();
            if (caller == null) {
                throw StackverseProblem.unauthorized("Authentication is required.");
            }
            where.and("owner = ?", caller.username());
            if (visibility != null) {
                where.and("visibility = ?", visibility);
            }
        } else {
            where.and("visibility = 'public' and status = 'active'");
        }
        if (!tags.isEmpty()) {
            where.and("tags @> ?::text[]", tags);
        }
        if (q != null && !q.isBlank()) {
            String pattern = "%" + requestParameters.escapeLike(q) + "%";
            where.and("(title ilike ? escape '\\' or notes ilike ? escape '\\')", pattern, pattern);
        }
        return where;
    }

    private static List<String> normalizeQueryTags(List<String> rawTags) {
        List<String> tags =
                rawTags.stream().map(tag -> tag.trim().toLowerCase(Locale.ROOT)).toList();
        Validator validator = new Validator();
        validator.check(
                tags.stream().allMatch(tag -> TAG_PATTERN.matcher(tag).matches()),
                "tag",
                "validation.tag.invalid");
        validator.throwIfInvalid();
        return tags;
    }

    static Bookmark bookmark(ResultSet rs) throws SQLException {
        return new Bookmark(
                (UUID) rs.getObject("id"),
                rs.getString("owner"),
                rs.getString("url"),
                rs.getString("title"),
                rs.getString("notes"),
                textArray(rs.getArray("tags")),
                rs.getString("visibility"),
                rs.getString("status"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    static BookmarkResponse bookmarkResponse(Bookmark bookmark) {
        List<String> tags = new ArrayList<>(bookmark.tags());
        tags.sort(Comparator.naturalOrder());
        return new BookmarkResponse(
                bookmark.id(),
                bookmark.url(),
                bookmark.title(),
                bookmark.notes(),
                tags,
                bookmark.visibility(),
                bookmark.status(),
                bookmark.owner(),
                bookmark.createdAt(),
                bookmark.updatedAt());
    }

    private Optional<Bookmark> findBookmark(Connection connection, UUID id) {
        return queryOne(
                connection,
                "select * from bookmarks where id = ?",
                List.of(id),
                BookmarkService::bookmark);
    }
}
