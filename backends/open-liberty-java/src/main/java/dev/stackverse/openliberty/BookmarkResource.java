package dev.stackverse.openliberty;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequestScoped
public class BookmarkResource extends ResourceSupport {
    @GET
    @Path("/api/v1/bookmarks")
    public Response listV1() throws SQLException {
        Paging paging = paging();
        ListFilters filters = listFilters();
        QueryParts parts = listingWhere(caller(), filters);
        ResponsePage result =
                queryPage(
                        "bookmarks",
                        parts.where(),
                        "order by created_at desc, id desc",
                        parts.params(),
                        paging,
                        this::bookmark);
        return DeprecationHeaders.addV1BookmarkHeaders(
                JsonSupport.json(page(result.items(), paging, result.total())));
    }

    @GET
    @Path("/api/v2/bookmarks")
    public Response listV2() throws SQLException {
        int size = cursorPageSize();
        ListFilters filters = listFilters();
        String rawCursor = single("cursor");
        Cursor cursor = rawCursor == null ? null : decodeCursor(rawCursor);
        QueryParts parts = listingWhere(caller(), filters);
        List<Object> params = new ArrayList<>(parts.params());
        String cursorWhere = "";
        if (cursor != null) {
            cursorWhere = " and (created_at < ? or (created_at = ? and id < ?))";
            params.add(cursor.createdAt());
            params.add(cursor.createdAt());
            params.add(cursor.id());
        }
        List<ApiModels.Bookmark> fetched = new ArrayList<>();
        try (Connection connection = runtime.connection();
                PreparedStatement statement =
                        prepare(
                                connection,
                                "select * from bookmarks where "
                                        + parts.where()
                                        + cursorWhere
                                        + " order by created_at desc, id desc limit ?",
                                append(params, size + 1))) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) fetched.add(bookmark(rs));
            }
        }
        List<ApiModels.Bookmark> items = fetched.size() > size ? fetched.subList(0, size) : fetched;
        String nextCursor = null;
        if (fetched.size() > size && !items.isEmpty()) {
            ApiModels.Bookmark last = items.get(items.size() - 1);
            nextCursor = encodeCursor(Instant.parse(last.createdAt()), UUID.fromString(last.id()));
        }
        return JsonSupport.json(new ApiModels.BookmarkCursorPage(items, nextCursor));
    }

    @POST
    @Path("/api/v1/bookmarks")
    public Response create(BookmarkInput body) throws SQLException {
        Caller caller = requireCaller();
        BookmarkInput input = bookmarkInput(body);
        UUID id = UUID.randomUUID();
        Instant now = Instant.now();
        ApiModels.Bookmark created;
        try (Connection connection = runtime.connection();
                PreparedStatement statement =
                        runtime.prepare(
                                connection,
                                """
             insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)
             values (?, ?, ?, ?, ?, ?::text[], ?, 'active', ?, ?)
             returning *
             """,
                                id,
                                caller.username(),
                                input.url(),
                                input.title(),
                                input.notes(),
                                input.tags().toArray(String[]::new),
                                input.visibility(),
                                now,
                                now)) {
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                created = bookmark(rs);
            }
        }
        return JsonSupport.created("/api/v1/bookmarks/" + id, created);
    }

    @GET
    @Path("/api/v1/bookmarks/{id}")
    public Response get(@PathParam("id") String rawId) throws SQLException {
        UUID id = uuid(rawId);
        ApiModels.Bookmark row;
        try (Connection connection = runtime.connection()) {
            row = findBookmark(connection, id, false);
        }
        if (row == null || !visibleTo(row, caller())) {
            throw ApiProblem.notFound();
        }
        return JsonSupport.json(row);
    }

    @PUT
    @Path("/api/v1/bookmarks/{id}")
    public Response update(@PathParam("id") String rawId, BookmarkInput body) {
        Caller caller = requireCaller();
        UUID id = uuid(rawId);
        BookmarkInput input = bookmarkInput(body);
        ApiModels.Bookmark updated =
                runtime.transaction(
                        connection -> {
                            ApiModels.Bookmark bookmark = findBookmark(connection, id, true);
                            if (bookmark == null || !caller.username().equals(bookmark.owner())) {
                                throw ApiProblem.notFound();
                            }
                            if ("hidden".equals(bookmark.status())
                                    && "public".equals(input.visibility())) {
                                throw ApiProblem.conflict(
                                        "This bookmark was hidden by moderation and cannot be made public.",
                                        "error.bookmark.hidden-publish");
                            }
                            try (PreparedStatement statement =
                                    runtime.prepare(
                                            connection,
                                            """
          update bookmarks
          set url = ?, title = ?, notes = ?, tags = ?::text[], visibility = ?, updated_at = ?
          where id = ?
          returning *
          """,
                                            input.url(),
                                            input.title(),
                                            input.notes(),
                                            input.tags().toArray(String[]::new),
                                            input.visibility(),
                                            Instant.now(),
                                            id)) {
                                try (ResultSet rs = statement.executeQuery()) {
                                    rs.next();
                                    return bookmark(rs);
                                }
                            }
                        });
        return JsonSupport.json(updated);
    }

    @DELETE
    @Path("/api/v1/bookmarks/{id}")
    public Response delete(@PathParam("id") String rawId) throws SQLException {
        Caller caller = requireCaller();
        UUID id = uuid(rawId);
        try (Connection connection = runtime.connection()) {
            ApiModels.Bookmark bookmark = findBookmark(connection, id, false);
            if (bookmark == null || !caller.username().equals(bookmark.owner())) {
                throw ApiProblem.notFound();
            }
            try (PreparedStatement statement =
                    runtime.prepare(connection, "delete from bookmarks where id = ?", id)) {
                statement.executeUpdate();
            }
        }
        return Response.noContent().build();
    }

    @GET
    @Path("/api/v1/tags")
    public Response tags() throws SQLException {
        Caller caller = requireCaller();
        List<ApiModels.TagCount> tags = new ArrayList<>();
        try (Connection connection = runtime.connection();
                PreparedStatement statement =
                        runtime.prepare(
                                connection,
                                """
             select tag, count(*)::int as count
             from bookmarks, unnest(tags) as tag
             where owner = ?
             group by tag
             order by count desc, tag asc
             """,
                                caller.username())) {
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) {
                    tags.add(new ApiModels.TagCount(rs.getString("tag"), rs.getInt("count")));
                }
            }
        }
        return JsonSupport.json(new ApiModels.Tags(tags));
    }
}
