package dev.stackverse.backend;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Controller
@ExecuteOn(TaskExecutors.BLOCKING)
final class BookmarksController {
    private final Database db;
    private final SecuritySupport security;
    private final InputValidator inputs;

    BookmarksController(Database db, SecuritySupport security, InputValidator inputs) {
        this.db = db;
        this.security = security;
        this.inputs = inputs;
    }

    @Get("/api/v1/bookmarks")
    MutableHttpResponse<PageResponse<BookmarkResponse>> listV1(HttpRequest<?> request) {
        int page = WebSupport.page(request);
        int size = WebSupport.size(request);
        ListQuery query = parseListQuery(request);
        Where where = query.where();
        long total = db.scalarLong("select count(*) from bookmarks " + where.sql(), where.args().toArray());
        List<Object> args = new ArrayList<>(where.args());
        args.add(size);
        args.add(WebSupport.offset(page, size));
        List<BookmarkResponse> items = db.query("""
                        select id, owner, url, title, notes, tags, visibility, status, created_at, updated_at
                        from bookmarks %s
                        order by created_at desc, id desc
                        limit ? offset ?
                        """.formatted(where.sql()),
                Models::bookmark, args.toArray()).stream().map(BookmarkResponse::from).toList();
        return WebSupport.withDeprecatedHeaders(HttpResponse.ok(WebSupport.pageResponse(items, page, size, total)));
    }

    @Get("/api/v2/bookmarks")
    BookmarkCursorPage listV2(HttpRequest<?> request) {
        int size = WebSupport.size(request);
        ListQuery query = parseListQuery(request);
        Cursor cursor = request.getParameters().getFirst("cursor").filter(value -> !value.isBlank()).map(WebSupport::decodeCursor).orElse(null);
        Where where = query.where();
        List<Object> args = new ArrayList<>(where.args());
        String cursorClause = "";
        if (cursor != null) {
            cursorClause = " and (created_at, id) < (?, ?)";
            args.add(cursor.createdAt());
            args.add(cursor.id());
        }
        args.add(size + 1);
        List<Bookmark> fetched = db.query("""
                        select id, owner, url, title, notes, tags, visibility, status, created_at, updated_at
                        from bookmarks %s%s
                        order by created_at desc, id desc
                        limit ?
                        """.formatted(where.sql(), cursorClause),
                Models::bookmark, args.toArray());
        String next = null;
        if (fetched.size() > size) {
            fetched = new ArrayList<>(fetched.subList(0, size));
            Bookmark last = fetched.getLast();
            next = WebSupport.encodeCursor(last.createdAt(), last.id());
        }
        return new BookmarkCursorPage(fetched.stream().map(BookmarkResponse::from).toList(), next);
    }

    @Post("/api/v1/bookmarks")
    MutableHttpResponse<BookmarkResponse> create(HttpRequest<?> request, @Body BookmarkInput body) {
        Identity caller = security.require(request);
        ValidBookmark input = validateBookmark(body);
        Instant now = WebSupport.now();
        Bookmark bookmark = new Bookmark(UUID.randomUUID(), caller.username(), input.url(), input.title(), input.notes(),
                input.tags(), input.visibility(), Models.ACTIVE, now, now);
        db.update("""
                insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, bookmark.id(), bookmark.owner(), bookmark.url(), bookmark.title(), bookmark.notes(),
                bookmark.tags(), bookmark.visibility(), bookmark.status(), bookmark.createdAt(), bookmark.updatedAt());
        return HttpResponse.created(BookmarkResponse.from(bookmark))
                .headers(headers -> headers.location(URI.create("/api/v1/bookmarks/" + bookmark.id())));
    }

    @Get("/api/v1/bookmarks/{id}")
    BookmarkResponse get(HttpRequest<?> request, @PathVariable String id) {
        Bookmark bookmark = byId(WebSupport.uuid(id, "id"));
        Identity identity = security.optional(request);
        String caller = identity == null ? "" : identity.username();
        if (!bookmark.visibleTo(caller)) {
            throw Problems.notFound();
        }
        return BookmarkResponse.from(bookmark);
    }

    @Put("/api/v1/bookmarks/{id}")
    BookmarkResponse update(HttpRequest<?> request, @PathVariable String id, @Body BookmarkInput body) {
        Identity caller = security.require(request);
        UUID bookmarkId = WebSupport.uuid(id, "id");
        return BookmarkResponse.from(db.inTx(connection -> {
            Bookmark bookmark = lockById(connection, bookmarkId);
            if (!bookmark.owner().equals(caller.username())) {
                throw Problems.notFound();
            }
            ValidBookmark input = validateBookmark(body);
            if (Models.HIDDEN.equals(bookmark.status()) && Models.PUBLIC.equals(input.visibility())) {
                throw Problems.conflictKey("error.bookmark.hidden-publish");
            }
            Bookmark updated = new Bookmark(bookmark.id(), bookmark.owner(), input.url(), input.title(), input.notes(),
                    input.tags(), input.visibility(), bookmark.status(), bookmark.createdAt(), WebSupport.now());
            db.update(connection,
                    "update bookmarks set url = ?, title = ?, notes = ?, tags = ?, visibility = ?, updated_at = ? where id = ?",
                    updated.url(), updated.title(), updated.notes(), updated.tags(), updated.visibility(), updated.updatedAt(), updated.id());
            return updated;
        }));
    }

    @Delete("/api/v1/bookmarks/{id}")
    HttpResponse<?> delete(HttpRequest<?> request, @PathVariable String id) {
        Identity caller = security.require(request);
        Bookmark bookmark = byId(WebSupport.uuid(id, "id"));
        if (!bookmark.owner().equals(caller.username())) {
            throw Problems.notFound();
        }
        db.update("delete from bookmarks where id = ?", bookmark.id());
        return HttpResponse.noContent();
    }

    @Get("/api/v1/tags")
    TagsResponse tags(HttpRequest<?> request) {
        Identity caller = security.require(request);
        List<TagCount> tags = db.query("""
                select tag, count(*) as count
                from bookmarks b cross join unnest(b.tags) as tag
                where b.owner = ?
                group by tag
                order by count(*) desc, tag
                """, rs -> new TagCount(rs.getString("tag"), rs.getLong("count")), caller.username());
        return new TagsResponse(tags);
    }

    Bookmark byId(UUID id) {
        return db.one("""
                select id, owner, url, title, notes, tags, visibility, status, created_at, updated_at
                from bookmarks where id = ?
                """, Models::bookmark, id);
    }

    Bookmark lockById(Connection connection, UUID id) throws SQLException {
        return db.one(connection, """
                select id, owner, url, title, notes, tags, visibility, status, created_at, updated_at
                from bookmarks where id = ? for update
                """, Models::bookmark, id);
    }

    private ListQuery parseListQuery(HttpRequest<?> request) {
        String q = request.getParameters().getFirst("q").orElse("");
        if (WebSupport.length(q) > 200) {
            throw Problems.badRequest("q is too long");
        }
        String visibility = request.getParameters().getFirst("visibility").orElse("");
        if (!visibility.isBlank() && !List.of(Models.PRIVATE, Models.PUBLIC).contains(visibility)) {
            throw Problems.badRequest("visibility must be one of: private, public");
        }
        List<String> tags = request.getParameters().getAll("tag").stream()
                .map(WebSupport::trim)
                .map(tag -> tag.toLowerCase(Locale.ROOT))
                .toList();
        Validator validator = new Validator();
        for (String tag : tags) {
            if (!WebSupport.TAG_PATTERN.matcher(tag).matches()) {
                validator.reject("tag", "validation.tag.invalid");
                break;
            }
        }
        validator.throwIfInvalid();
        String caller = "";
        if (!Models.PUBLIC.equals(visibility)) {
            caller = security.require(request).username();
        }
        return new ListQuery(caller, visibility, tags, q);
    }

    private ValidBookmark validateBookmark(BookmarkInput body) {
        String url = WebSupport.trim(body == null ? null : body.url());
        String title = WebSupport.trim(body == null ? null : body.title());
        String notes = body == null ? null : body.notes();
        List<String> tags = WebSupport.normalizeTags(body == null ? null : body.tags());
        String visibility = body == null || body.visibility() == null ? Models.PRIVATE : body.visibility();
        if (!List.of(Models.PRIVATE, Models.PUBLIC).contains(visibility)) {
            throw Problems.badRequest("visibility must be one of: private, public");
        }
        inputs.validate(body);
        return new ValidBookmark(url, title, notes, tags, visibility);
    }

    private record ValidBookmark(String url, String title, String notes, List<String> tags, String visibility) {
    }

    private record ListQuery(String caller, String visibility, List<String> tags, String q) {
        Where where() {
            List<String> clauses = new ArrayList<>();
            List<Object> args = new ArrayList<>();
            if (Models.PUBLIC.equals(visibility)) {
                clauses.add("visibility = 'public' and status = 'active'");
            } else {
                clauses.add("owner = ?");
                args.add(caller);
                if (!visibility.isBlank()) {
                    clauses.add("visibility = ?");
                    args.add(visibility);
                }
            }
            if (!tags.isEmpty()) {
                clauses.add("tags @> ?");
                args.add(tags);
            }
            if (q != null && !q.isBlank()) {
                String pattern = "%" + WebSupport.escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
                clauses.add("(lower(title) like ? escape '\\' or lower(coalesce(notes, '')) like ? escape '\\')");
                args.add(pattern);
                args.add(pattern);
            }
            return new Where("where " + String.join(" and ", clauses), args);
        }
    }

    private record Where(String sql, List<Object> args) {
    }
}

record TagsResponse(List<TagCount> tags) {
}
