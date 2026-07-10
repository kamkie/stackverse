package dev.stackverse.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.eclipse.microprofile.jwt.JsonWebToken;

@ApplicationScoped
public class BookmarkService extends ServiceSupport {
    @Inject
    public BookmarkService(
            DataSource dataSource,
            JsonWebToken jwt,
            SecurityIdentity securityIdentity,
            ObjectMapper mapper,
            Localizer localizer) {
        super(dataSource, jwt, securityIdentity, mapper, localizer);
    }

    public Response listBookmarksV1(RequestContext request) {
        int page = pagingPage(request);
        int size = pageSize(request);
        SqlWhere query = parseBookmarkListQuery(request);
        Page<Bookmark> result =
                withConnection(
                        connection -> {
                            long total =
                                    scalarLong(
                                            connection,
                                            "select count(*) from bookmarks " + query.sql(),
                                            query.params());
                            List<Object> params = new ArrayList<>(query.params());
                            params.add(size);
                            params.add(offset(page, size));
                            List<Bookmark> items =
                                    query(
                                            connection,
                                            "select * from bookmarks "
                                                    + query.sql()
                                                    + " order by created_at desc, id desc limit ? offset ?",
                                            params,
                                            ServiceSupport::bookmark);
                            return new Page<>(items, page, size, total);
                        });
        List<BookmarkResponse> items =
                result.items().stream().map(ServiceSupport::bookmarkResponse).toList();
        return v1BookmarksDeprecationHeaders(
                Response.ok(pageResponse(items, result.page(), result.size(), result.totalItems()))
                        .build());
    }

    public Response listBookmarksV2(RequestContext request) {
        int size = pageSize(request);
        SqlWhere query = parseBookmarkListQuery(request);
        Cursor cursor =
                Optional.ofNullable(singleParam(request, "cursor"))
                        .filter(value -> !value.isBlank())
                        .map(Cursor::decode)
                        .orElse(null);
        List<Bookmark> fetched =
                withConnection(
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
                                    ServiceSupport::bookmark);
                        });
        List<Bookmark> items = fetched.size() > size ? fetched.subList(0, size) : fetched;
        String nextCursor = null;
        if (fetched.size() > size && !items.isEmpty()) {
            Bookmark last = items.get(items.size() - 1);
            nextCursor = new Cursor(last.createdAt(), last.id()).encode();
        }
        return Response.ok(
                        new CursorPage<>(
                                items.stream().map(ServiceSupport::bookmarkResponse).toList(),
                                nextCursor))
                .build();
    }

    public Response createBookmark(BookmarkInput input) {
        Caller caller = requireCaller();
        requireBookmarkVisibility(input.visibility());
        Bookmark created =
                withConnection(
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
                                            ServiceSupport::bookmark)
                                    .orElseThrow();
                        });
        return Response.created(URI.create("/api/v1/bookmarks/" + created.id()))
                .entity(bookmarkResponse(created))
                .build();
    }

    public Response getBookmark(String rawId) {
        UUID id = parseUuid(rawId);
        Caller caller = currentCaller();
        Bookmark bookmark =
                withConnection(connection -> findBookmark(connection, id))
                        .orElseThrow(StackverseProblem::notFound);
        if (!bookmark.visibleTo(caller == null ? null : caller.username())) {
            throw StackverseProblem.notFound();
        }
        return Response.ok(bookmarkResponse(bookmark)).build();
    }

    public Response updateBookmark(String rawId, BookmarkInput input) {
        Caller caller = requireCaller();
        UUID id = parseUuid(rawId);
        requireBookmarkVisibility(input.visibility());
        Bookmark updated =
                inTransaction(
                        connection -> {
                            Bookmark bookmark =
                                    queryOne(
                                                    connection,
                                                    "select * from bookmarks where id = ? for update",
                                                    List.of(id),
                                                    ServiceSupport::bookmark)
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
                                            ServiceSupport::bookmark)
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
        Caller caller = requireCaller();
        UUID id = parseUuid(rawId);
        withConnection(
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
        Caller caller = requireCaller();
        List<TagCount> tags =
                withConnection(
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
}
