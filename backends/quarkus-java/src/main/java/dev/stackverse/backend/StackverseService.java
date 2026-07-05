package dev.stackverse.backend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import org.postgresql.util.PSQLException;

import javax.sql.DataSource;
import java.net.URI;
import java.security.MessageDigest;
import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@ApplicationScoped
public class StackverseService {
    private static final Logger LOG = Logger.getLogger(StackverseService.class);

    private static final String V1_BOOKMARKS_DEPRECATION = "@1782864000";
    private static final String V1_BOOKMARKS_SUNSET = "Thu, 01 Jul 2027 00:00:00 GMT";
    private static final String V1_BOOKMARKS_SUCCESSOR = "</api/v2/bookmarks>; rel=\"successor-version\"";

    private static final Pattern TAG_PATTERN = Pattern.compile("^[a-z0-9-]{1,30}$");
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9-]+(\\.[a-z0-9-]+)*$");
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("^[a-z]{2}$");

    private final DataSource dataSource;
    private final JsonWebToken jwt;
    private final SecurityIdentity securityIdentity;
    private final ObjectMapper mapper;
    private final Localizer localizer;

    @Inject
    public StackverseService(DataSource dataSource, JsonWebToken jwt, SecurityIdentity securityIdentity,
                             ObjectMapper mapper, Localizer localizer) {
        this.dataSource = dataSource;
        this.jwt = jwt;
        this.securityIdentity = securityIdentity;
        this.mapper = mapper;
        this.localizer = localizer;
    }

    public Response healthz() {
        return Response.ok().build();
    }

    public Response readyz() {
        try (Connection connection = dataSource.getConnection();
             PreparedStatement statement = connection.prepareStatement("select 1")) {
            statement.executeQuery().close();
            return Response.ok().build();
        } catch (SQLException error) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        }
    }

    public Response listBookmarksV1(RequestContext request) {
        int page = pagingPage(request);
        int size = pageSize(request);
        SqlWhere query = parseBookmarkListQuery(request);
        Page<Bookmark> result = withConnection(connection -> {
            long total = scalarLong(connection, "select count(*) from bookmarks " + query.sql(), query.params());
            List<Object> params = new ArrayList<>(query.params());
            params.add(size);
            params.add(offset(page, size));
            List<Bookmark> items = query(connection,
                    "select * from bookmarks " + query.sql()
                            + " order by created_at desc, id desc limit ? offset ?",
                    params,
                    StackverseService::bookmark);
            return new Page<>(items, page, size, total);
        });
        List<Map<String, Object>> items = result.items().stream().map(StackverseService::bookmarkResponse).toList();
        return v1BookmarksDeprecationHeaders(
                Response.ok(pageResponse(items, result.page(), result.size(), result.totalItems())).build());
    }

    public Response listBookmarksV2(RequestContext request) {
        int size = pageSize(request);
        SqlWhere query = parseBookmarkListQuery(request);
        Cursor cursor = Optional.ofNullable(singleParam(request, "cursor")).filter(value -> !value.isBlank())
                .map(Cursor::decode).orElse(null);
        List<Bookmark> fetched = withConnection(connection -> {
            String where = query.sql();
            List<Object> params = new ArrayList<>(query.params());
            if (cursor != null) {
                where += " and (created_at, id) < (?, ?)";
                params.add(cursor.createdAt());
                params.add(cursor.id());
            }
            params.add(size + 1);
            return query(connection,
                    "select * from bookmarks " + where
                            + " order by created_at desc, id desc limit ?",
                    params,
                    StackverseService::bookmark);
        });
        List<Bookmark> items = fetched.size() > size ? fetched.subList(0, size) : fetched;
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items.stream().map(StackverseService::bookmarkResponse).toList());
        if (fetched.size() > size && !items.isEmpty()) {
            Bookmark last = items.get(items.size() - 1);
            body.put("nextCursor", new Cursor(last.createdAt(), last.id()).encode());
        }
        return Response.ok(body).build();
    }

    public Response createBookmark(JsonNode body) {
        Caller caller = requireCaller();
        BookmarkInput input = validateBookmarkInput(body);
        Bookmark created = withConnection(connection -> {
            UUID id = UUID.randomUUID();
            Instant now = now();
            return queryOne(connection,
                    "insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)"
                            + " values (?, ?, ?, ?, ?, ?::text[], ?, 'active', ?, ?) returning *",
                    params(id, caller.username(), input.url(), input.title(), input.notes(), input.tags(),
                            input.visibility(), now, now),
                    StackverseService::bookmark).orElseThrow();
        });
        return Response.created(URI.create("/api/v1/bookmarks/" + created.id()))
                .entity(bookmarkResponse(created))
                .build();
    }

    public Response getBookmark(String rawId) {
        UUID id = parseUuid(rawId);
        Caller caller = currentCaller();
        Bookmark bookmark = withConnection(connection -> findBookmark(connection, id)).orElseThrow(StackverseProblem::notFound);
        if (!bookmark.visibleTo(caller == null ? null : caller.username())) {
            throw StackverseProblem.notFound();
        }
        return Response.ok(bookmarkResponse(bookmark)).build();
    }

    public Response updateBookmark(String rawId, JsonNode body) {
        Caller caller = requireCaller();
        UUID id = parseUuid(rawId);
        BookmarkInput input = validateBookmarkInput(body);
        Bookmark updated = inTransaction(connection -> {
            Bookmark bookmark = queryOne(connection, "select * from bookmarks where id = ? for update", List.of(id),
                    StackverseService::bookmark).orElseThrow(StackverseProblem::notFound);
            if (!bookmark.owner().equals(caller.username())) {
                throw StackverseProblem.notFound();
            }
            if ("hidden".equals(bookmark.status()) && "public".equals(input.visibility())) {
                throw StackverseProblem.conflictKey("error.bookmark.hidden-publish");
            }
            return queryOne(connection,
                    "update bookmarks set url = ?, title = ?, notes = ?, tags = ?::text[], visibility = ?, updated_at = ?"
                            + " where id = ? returning *",
                    params(input.url(), input.title(), input.notes(), input.tags(), input.visibility(), now(), id),
                    StackverseService::bookmark).orElseThrow();
        });
        return Response.ok(bookmarkResponse(updated)).build();
    }

    public Response deleteBookmark(String rawId) {
        Caller caller = requireCaller();
        UUID id = parseUuid(rawId);
        withConnection(connection -> {
            Bookmark bookmark = findBookmark(connection, id).orElseThrow(StackverseProblem::notFound);
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
        List<Map<String, Object>> tags = withConnection(connection -> query(connection,
                "select tag, count(*) as count from bookmarks, unnest(tags) as tag"
                        + " where owner = ? group by tag order by count desc, tag",
                List.of(caller.username()),
                rs -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("tag", rs.getString("tag"));
                    row.put("count", rs.getLong("count"));
                    return row;
                }));
        return Response.ok(Map.of("tags", tags)).build();
    }

    public Response listMessages(RequestContext request) {
        int page = pagingPage(request);
        int size = pageSize(request);
        String key = singleParam(request, "key");
        String language = singleParam(request, "language");
        String q = singleParam(request, "q");
        maxLength(q, 200, "q");
        Map<String, Object> response = withConnection(connection -> {
            SqlWhere where = new SqlWhere();
            if (key != null) {
                where.and("key = ?", key);
            }
            if (language != null) {
                where.and("language = ?", language);
            }
            if (q != null && !q.isBlank()) {
                where.and("(key ilike ? escape '\\' or text ilike ? escape '\\')",
                        "%" + escapeLike(q) + "%",
                        "%" + escapeLike(q) + "%");
            }
            long total = scalarLong(connection, "select count(*) from messages " + where.sql(), where.params());
            List<Object> params = new ArrayList<>(where.params());
            params.add(size);
            params.add(offset(page, size));
            List<Map<String, Object>> items = query(connection,
                    "select * from messages " + where.sql()
                            + " order by key, language limit ? offset ?",
                    params,
                    rs -> messageResponse(message(rs)));
            return pageResponse(items, page, size, total);
        });
        return etag(request, response, null);
    }

    public Response messageBundle(RequestContext request) {
        String language = localizer.resolveLanguage(request.uriInfo(), request.headers());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("language", language);
        body.put("messages", localizer.bundle(language));
        return etag(request, body, Map.of("Content-Language", language));
    }

    public Response getMessage(RequestContext request, String rawId) {
        UUID id = parseUuid(rawId);
        Map<String, Object> body = withConnection(connection -> queryOne(connection,
                "select * from messages where id = ?",
                List.of(id),
                rs -> messageResponse(message(rs))).orElseThrow(StackverseProblem::notFound));
        return etag(request, body, null);
    }

    public Response createMessage(JsonNode body) {
        Caller caller = requireRole("admin");
        MessageInput input = validateMessageInput(body);
        Message created = inTransaction(connection -> {
            if (messageConflict(connection, input.key(), input.language(), null)) {
                throw duplicateMessage(input);
            }
            Message inserted;
            try {
                Instant now = now();
                inserted = queryOne(connection,
                        "insert into messages (id, key, language, text, description, created_at, updated_at)"
                                + " values (?, ?, ?, ?, ?, ?, ?) returning *",
                        params(UUID.randomUUID(), input.key(), input.language(), input.text(), input.description(), now, now),
                        StackverseService::message).orElseThrow();
            } catch (RuntimeException error) {
                if (isUniqueViolation(error)) {
                    throw duplicateMessage(input);
                }
                throw error;
            }
            recordAudit(connection, caller.username(), "message.created", "message", inserted.id().toString(), snapshot(inserted));
            return inserted;
        });
        StackverseLog.event(LOG, Logger.Level.INFO, "message_created", "success", "Message created",
                Map.of("actor", caller.username(), "resource_type", "message", "resource_id", created.id().toString(),
                        "message_key", created.key(), "language", created.language()));
        return Response.created(URI.create("/api/v1/messages/" + created.id()))
                .entity(messageResponse(created))
                .build();
    }

    public Response updateMessage(String rawId, JsonNode body) {
        Caller caller = requireRole("admin");
        UUID id = parseUuid(rawId);
        MessageInput input = validateMessageInput(body);
        Message updated = inTransaction(connection -> {
            queryOne(connection, "select id from messages where id = ?", List.of(id), rs -> rs.getObject("id"))
                    .orElseThrow(StackverseProblem::notFound);
            if (messageConflict(connection, input.key(), input.language(), id)) {
                throw duplicateMessage(input);
            }
            Message row;
            try {
                row = queryOne(connection,
                        "update messages set key = ?, language = ?, text = ?, description = ?, updated_at = ?"
                                + " where id = ? returning *",
                        params(input.key(), input.language(), input.text(), input.description(), now(), id),
                        StackverseService::message).orElseThrow();
            } catch (RuntimeException error) {
                if (isUniqueViolation(error)) {
                    throw duplicateMessage(input);
                }
                throw error;
            }
            recordAudit(connection, caller.username(), "message.updated", "message", row.id().toString(), snapshot(row));
            return row;
        });
        StackverseLog.event(LOG, Logger.Level.INFO, "message_updated", "success", "Message updated",
                Map.of("actor", caller.username(), "resource_type", "message", "resource_id", updated.id().toString(),
                        "message_key", updated.key(), "language", updated.language()));
        return Response.ok(messageResponse(updated)).build();
    }

    public Response deleteMessage(String rawId) {
        Caller caller = requireRole("admin");
        UUID id = parseUuid(rawId);
        Message deleted = inTransaction(connection -> {
            Message row = queryOne(connection, "delete from messages where id = ? returning *", List.of(id),
                    StackverseService::message).orElseThrow(StackverseProblem::notFound);
            recordAudit(connection, caller.username(), "message.deleted", "message", row.id().toString(), snapshot(row));
            return row;
        });
        StackverseLog.event(LOG, Logger.Level.INFO, "message_deleted", "success", "Message deleted",
                Map.of("actor", caller.username(), "resource_type", "message", "resource_id", deleted.id().toString(),
                        "message_key", deleted.key(), "language", deleted.language()));
        return Response.noContent().build();
    }

    public Response reportBookmark(String rawId, JsonNode body) {
        Caller caller = requireCaller();
        UUID bookmarkId = parseUuid(rawId);
        ReportInput input = validateReportInput(body);
        Report report = inTransaction(connection -> {
            Optional<Bookmark> bookmark = queryOne(connection,
                    "select * from bookmarks where id = ? for update", List.of(bookmarkId), StackverseService::bookmark);
            if (bookmark.isEmpty()
                    || !"public".equals(bookmark.get().visibility())
                    || !"active".equals(bookmark.get().status())) {
                throw StackverseProblem.notFound();
            }
            boolean duplicate = scalarLong(connection,
                    "select count(*) from reports where bookmark_id = ? and reporter = ? and status = 'open'",
                    List.of(bookmarkId, caller.username())) > 0;
            if (duplicate) {
                throw StackverseProblem.conflict("You already have an open report on this bookmark.");
            }
            try {
                return queryOne(connection,
                        "insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)"
                                + " values (?, ?, ?, ?, ?, 'open', ?) returning *",
                        params(UUID.randomUUID(), bookmarkId, caller.username(), input.reason(), input.comment(), now()),
                        StackverseService::report).orElseThrow();
            } catch (RuntimeException error) {
                if (isUniqueViolation(error)) {
                    throw StackverseProblem.conflict("You already have an open report on this bookmark.");
                }
                throw error;
            }
        });
        StackverseLog.event(LOG, Logger.Level.INFO, "report_created", "success", "Report created on a public bookmark",
                Map.of("actor", caller.username(), "resource_type", "report", "resource_id", report.id().toString(),
                        "bookmark_id", bookmarkId.toString(), "reason", report.reason()));
        return Response.status(Response.Status.CREATED).entity(reportResponse(report)).build();
    }

    public Response listMyReports(RequestContext request) {
        Caller caller = requireCaller();
        int page = pagingPage(request);
        int size = pageSize(request);
        String status = singleParam(request, "status");
        if (status != null && !validReportStatus(status)) {
            throw StackverseProblem.badRequest("status must be one of: open, dismissed, actioned");
        }
        Map<String, Object> body = withConnection(connection -> {
            SqlWhere where = new SqlWhere();
            where.and("reporter = ?", caller.username());
            if (status != null) {
                where.and("status = ?", status);
            }
            long total = scalarLong(connection, "select count(*) from reports " + where.sql(), where.params());
            List<Object> params = new ArrayList<>(where.params());
            params.add(size);
            params.add(offset(page, size));
            List<Map<String, Object>> items = query(connection,
                    "select * from reports " + where.sql() + " order by created_at desc, id desc limit ? offset ?",
                    params,
                    rs -> reportResponse(report(rs)));
            return pageResponse(items, page, size, total);
        });
        return Response.ok(body).build();
    }

    public Response updateMyReport(String rawId, JsonNode body) {
        Caller caller = requireCaller();
        UUID id = parseUuid(rawId);
        ReportInput input = validateReportInput(body);
        Report updated = inTransaction(connection -> {
            Report report = ownReportForUpdate(connection, caller.username(), id);
            if (!"open".equals(report.status())) {
                throw StackverseProblem.conflict("The report has already been resolved.");
            }
            return queryOne(connection,
                    "update reports set reason = ?, comment = ? where id = ? returning *",
                    params(input.reason(), input.comment(), id),
                    StackverseService::report).orElseThrow();
        });
        StackverseLog.event(LOG, Logger.Level.INFO, "report_updated", "success", "Report updated by its reporter",
                Map.of("actor", caller.username(), "resource_type", "report", "resource_id", updated.id().toString(),
                        "bookmark_id", updated.bookmarkId().toString(), "reason", updated.reason()));
        return Response.ok(reportResponse(updated)).build();
    }

    public Response withdrawReport(String rawId) {
        Caller caller = requireCaller();
        UUID id = parseUuid(rawId);
        Report withdrawn = inTransaction(connection -> {
            Report report = ownReportForUpdate(connection, caller.username(), id);
            if (!"open".equals(report.status())) {
                throw StackverseProblem.conflict("The report has already been resolved.");
            }
            execute(connection, "delete from reports where id = ?", List.of(id));
            return report;
        });
        StackverseLog.event(LOG, Logger.Level.INFO, "report_withdrawn", "success", "Report withdrawn by its reporter",
                Map.of("actor", caller.username(), "resource_type", "report", "resource_id", withdrawn.id().toString(),
                        "bookmark_id", withdrawn.bookmarkId().toString()));
        return Response.noContent().build();
    }

    public Response listReportQueue(RequestContext request) {
        requireRole("moderator");
        int page = pagingPage(request);
        int size = pageSize(request);
        String status = Optional.ofNullable(singleParam(request, "status")).orElse("open");
        if (!validReportStatus(status)) {
            throw StackverseProblem.badRequest("status must be one of: open, dismissed, actioned");
        }
        Map<String, Object> body = withConnection(connection -> {
            long total = scalarLong(connection, "select count(*) from reports where status = ?", List.of(status));
            List<Map<String, Object>> items = query(connection,
                    "select * from reports where status = ? order by created_at asc, id asc limit ? offset ?",
                    List.of(status, size, offset(page, size)),
                    rs -> reportResponse(report(rs)));
            return pageResponse(items, page, size, total);
        });
        return Response.ok(body).build();
    }

    public Response resolveReport(String rawId, JsonNode body) {
        Caller caller = requireRole("moderator");
        UUID id = parseUuid(rawId);
        ResolutionInput input = validateResolutionInput(body);
        List<Runnable> events = new ArrayList<>();
        Report resolved = inTransaction(connection -> {
            if ("actioned".equals(input.resolution())) {
                UUID bookmarkId = queryOne(connection, "select bookmark_id from reports where id = ?", List.of(id),
                        rs -> (UUID) rs.getObject("bookmark_id")).orElseThrow(StackverseProblem::notFound);
                queryOne(connection, "select id from bookmarks where id = ? for update", List.of(bookmarkId),
                        rs -> rs.getObject("id")).orElseThrow(StackverseProblem::notFound);
            }
            Report locked = queryOne(connection, "select * from reports where id = ? for update", List.of(id),
                    StackverseService::report).orElseThrow(StackverseProblem::notFound);
            if ("open".equals(input.resolution())) {
                return reopenReport(connection, caller, locked, events);
            }
            Report primary = resolveOne(connection, caller, locked, input.resolution(), input.note(), false, events);
            if ("actioned".equals(input.resolution())) {
                hideBookmark(connection, caller, locked.bookmarkId(), input.note(), events);
                List<Report> siblings = query(connection,
                        "select * from reports where bookmark_id = ? and status = 'open' and id <> ? order by id for update",
                        List.of(locked.bookmarkId(), locked.id()),
                        StackverseService::report);
                for (Report sibling : siblings) {
                    resolveOne(connection, caller, sibling, "actioned", input.note(), true, events);
                }
            }
            return primary;
        });
        events.forEach(Runnable::run);
        return Response.ok(reportResponse(resolved)).build();
    }

    public Response setBookmarkStatus(String rawId, JsonNode body) {
        Caller caller = requireRole("moderator");
        UUID id = parseUuid(rawId);
        BookmarkStatusInput input = validateBookmarkStatusInput(body);
        StatusChange change = inTransaction(connection -> {
            Bookmark bookmark = queryOne(connection, "select * from bookmarks where id = ? for update", List.of(id),
                    StackverseService::bookmark).orElseThrow(StackverseProblem::notFound);
            Bookmark updated = queryOne(connection,
                    "update bookmarks set status = ?, updated_at = ? where id = ? returning *",
                    List.of(input.status(), now(), id),
                    StackverseService::bookmark).orElseThrow();
            recordAudit(connection, caller.username(), "bookmark.status-changed", "bookmark", id.toString(),
                    detail("from", bookmark.status(), "to", input.status(), "note", input.note()));
            return new StatusChange(bookmark.status(), updated);
        });
        StackverseLog.event(LOG, Logger.Level.INFO, "bookmark_status_changed", "success",
                "Bookmark moderation status changed",
                Map.of("actor", caller.username(), "resource_type", "bookmark", "resource_id", id.toString(),
                        "from", change.previous(), "to", change.bookmark().status()));
        return Response.ok(bookmarkResponse(change.bookmark())).build();
    }

    public Response listUsers(RequestContext request) {
        requireRole("admin");
        int page = pagingPage(request);
        int size = pageSize(request);
        String q = singleParam(request, "q");
        maxLength(q, 100, "q");
        String status = singleParam(request, "status");
        if (status != null && !Set.of("active", "blocked").contains(status)) {
            throw StackverseProblem.badRequest("status must be one of: active, blocked");
        }
        Map<String, Object> body = withConnection(connection -> {
            SqlWhere where = new SqlWhere();
            if (q != null && !q.isBlank()) {
                where.and("u.username ilike ? escape '\\'", "%" + escapeLike(q) + "%");
            }
            if (status != null) {
                where.and("u.status = ?", status);
            }
            long total = scalarLong(connection, "select count(*) from user_accounts u " + where.sql(), where.params());
            List<Object> params = new ArrayList<>(where.params());
            params.add(size);
            params.add(offset(page, size));
            List<Map<String, Object>> items = query(connection,
                    userAccountSelect() + " " + where.sql()
                            + " order by u.last_seen desc, u.username asc limit ? offset ?",
                    params,
                    rs -> userAccountResponse(userAccount(rs)));
            return pageResponse(items, page, size, total);
        });
        return Response.ok(body).build();
    }

    public Response getUser(String username) {
        requireRole("admin");
        UserAccount account = withConnection(connection -> findUserAccount(connection, username))
                .orElseThrow(StackverseProblem::notFound);
        return Response.ok(userAccountResponse(account)).build();
    }

    public Response setUserStatus(String username, JsonNode body) {
        Caller caller = requireRole("admin");
        UserStatusInput input = validateUserStatusInput(body);
        if ("blocked".equals(input.status())) {
            Validator validator = new Validator();
            validator.check(input.reason() != null && !input.reason().isBlank(),
                    "reason", "validation.block.reason.required");
            validator.check(length(input.reason()) <= 1000,
                    "reason", "validation.block.reason.too-long");
            validator.throwIfInvalid();
            if (username.equals(caller.username())) {
                throw StackverseProblem.conflict("Admins cannot block themselves.");
            }
        }
        inTransaction(connection -> {
            queryOne(connection, "select username from user_accounts where username = ? for update", List.of(username),
                    rs -> rs.getString("username")).orElseThrow(StackverseProblem::notFound);
            if ("blocked".equals(input.status())) {
                execute(connection,
                        "update user_accounts set status = 'blocked', blocked_reason = ? where username = ?",
                        params(input.reason(), username));
                recordAudit(connection, caller.username(), "user.blocked", "user", username,
                        Map.of("reason", input.reason()));
            } else {
                execute(connection,
                        "update user_accounts set status = 'active', blocked_reason = null where username = ?",
                        List.of(username));
                recordAudit(connection, caller.username(), "user.unblocked", "user", username, null);
            }
            return null;
        });
        StackverseLog.event(LOG, Logger.Level.INFO,
                "blocked".equals(input.status()) ? "user_blocked" : "user_unblocked",
                "success",
                "blocked".equals(input.status()) ? "User account blocked" : "User account unblocked",
                Map.of("actor", caller.username(), "resource_type", "user", "resource_id", username));
        UserAccount account = withConnection(connection -> findUserAccount(connection, username))
                .orElseThrow(StackverseProblem::notFound);
        return Response.ok(userAccountResponse(account)).build();
    }

    public Response auditLog(RequestContext request) {
        requireRole("admin");
        int page = pagingPage(request);
        int size = pageSize(request);
        Map<String, Object> body = withConnection(connection -> {
            SqlWhere where = new SqlWhere();
            equalFilter(request, where, "actor", "actor");
            equalFilter(request, where, "action", "action");
            equalFilter(request, where, "target_type", "targetType");
            equalFilter(request, where, "target_id", "targetId");
            Instant from = timeParam(request, "from");
            Instant to = timeParam(request, "to");
            if (from != null) {
                where.and("created_at >= ?", from);
            }
            if (to != null) {
                where.and("created_at <= ?", to);
            }
            long total = scalarLong(connection, "select count(*) from audit_entries " + where.sql(), where.params());
            List<Object> params = new ArrayList<>(where.params());
            params.add(size);
            params.add(offset(page, size));
            List<Map<String, Object>> items = query(connection,
                    "select * from audit_entries " + where.sql()
                            + " order by created_at desc, id desc limit ? offset ?",
                    params,
                    rs -> auditResponse(audit(rs)));
            return pageResponse(items, page, size, total);
        });
        return Response.ok(body).build();
    }

    public Response stats(RequestContext request) {
        requireRole("moderator");
        Map<String, Object> body = withConnection(connection -> {
            Map<String, Object> totals = new LinkedHashMap<>();
            totals.put("users", scalarLong(connection, "select count(*) from user_accounts", List.of()));
            totals.put("bookmarks", scalarLong(connection, "select count(*) from bookmarks", List.of()));
            totals.put("publicBookmarks", scalarLong(connection,
                    "select count(*) from bookmarks where visibility = 'public'", List.of()));
            totals.put("hiddenBookmarks", scalarLong(connection,
                    "select count(*) from bookmarks where status = 'hidden'", List.of()));
            totals.put("openReports", scalarLong(connection,
                    "select count(*) from reports where status = 'open'", List.of()));

            LocalDate today = LocalDate.now(ZoneOffset.UTC);
            LocalDate from = today.minusDays(29);
            Map<LocalDate, Long> bookmarksCreated = countPerDay(connection, "bookmarks", "created_at", from);
            Map<LocalDate, Long> activeUsers = countPerDay(connection, "user_accounts", "last_seen", from);
            List<Map<String, Object>> daily = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                LocalDate date = from.plusDays(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("date", date.toString());
                row.put("bookmarksCreated", bookmarksCreated.getOrDefault(date, 0L));
                row.put("activeUsers", activeUsers.getOrDefault(date, 0L));
                daily.add(row);
            }
            List<Map<String, Object>> topTags = query(connection,
                    "select tag, count(*) as count from bookmarks, unnest(tags) as tag"
                            + " group by tag order by count desc, tag asc limit 10",
                    List.of(),
                    rs -> {
                        Map<String, Object> row = new LinkedHashMap<>();
                        row.put("tag", rs.getString("tag"));
                        row.put("count", rs.getLong("count"));
                        return row;
                    });
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("totals", totals);
            response.put("daily", daily);
            response.put("topTags", topTags);
            return response;
        });
        return etag(request, body, null);
    }

    public Response me() {
        Caller caller = requireCaller();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", caller.username());
        putIfPresent(body, "name", caller.name());
        putIfPresent(body, "email", caller.email());
        body.put("roles", caller.roles().stream()
                .filter(role -> role.equals("moderator") || role.equals("admin"))
                .sorted()
                .toList());
        return Response.ok(body).build();
    }

    private SqlWhere parseBookmarkListQuery(RequestContext request) {
        String q = singleParam(request, "q");
        maxLength(q, 200, "q");
        String visibility = singleParam(request, "visibility");
        if (visibility != null && !Set.of("private", "public").contains(visibility)) {
            throw StackverseProblem.badRequest("visibility must be one of: private, public");
        }
        List<String> tags = normalizeQueryTags(queryParams(request).getOrDefault("tag", List.of()));
        SqlWhere where = new SqlWhere();
        if (!"public".equals(visibility)) {
            Caller caller = currentCaller();
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
            String pattern = "%" + escapeLike(q) + "%";
            where.and("(title ilike ? escape '\\' or notes ilike ? escape '\\')", pattern, pattern);
        }
        return where;
    }

    private BookmarkInput validateBookmarkInput(JsonNode raw) {
        JsonNode body = objectBody(raw);
        Validator validator = new Validator();
        String url = text(body, "url", "").trim();
        if (url.isEmpty()) {
            validator.reject("url", "validation.url.required");
        } else {
            validator.check(length(url) <= 2000 && isHttpUrl(url), "url", "validation.url.invalid");
        }
        String title = text(body, "title", "").trim();
        validator.check(!title.isEmpty(), "title", "validation.title.required");
        validator.check(length(title) <= 200, "title", "validation.title.too-long");
        String notes = nullableText(body, "notes");
        validator.check(length(notes) <= 4000, "notes", "validation.notes.too-long");
        List<String> tags = normalizeBodyTags(body.get("tags"));
        validator.check(tags.size() <= 10, "tags", "validation.tags.too-many");
        validator.check(tags.stream().allMatch(tag -> TAG_PATTERN.matcher(tag).matches()), "tags", "validation.tag.invalid");
        JsonNode visibilityNode = body.get("visibility");
        String visibility = "private";
        if (visibilityNode != null && !visibilityNode.isNull()) {
            visibility = visibilityNode.isTextual() ? visibilityNode.asText() : null;
        }
        if (!Set.of("private", "public").contains(visibility)) {
            throw StackverseProblem.badRequest("visibility must be one of: private, public");
        }
        validator.throwIfInvalid();
        return new BookmarkInput(url, title, notes, tags, visibility);
    }

    private MessageInput validateMessageInput(JsonNode raw) {
        JsonNode body = objectBody(raw);
        Validator validator = new Validator();
        String key = text(body, "key", "").trim();
        validator.check(KEY_PATTERN.matcher(key).matches() && length(key) <= 150,
                "key", "validation.message.key.invalid");
        String language = text(body, "language", "").trim();
        validator.check(LANGUAGE_PATTERN.matcher(language).matches(),
                "language", "validation.message.language.invalid");
        String text = text(body, "text", "");
        validator.check(!text.isEmpty(), "text", "validation.message.text.required");
        validator.check(length(text) <= 2000, "text", "validation.message.text.too-long");
        String description = nullableText(body, "description");
        validator.check(length(description) <= 1000, "description", "validation.message.description.too-long");
        validator.throwIfInvalid();
        return new MessageInput(key, language, text, description);
    }

    private ReportInput validateReportInput(JsonNode raw) {
        JsonNode body = objectBody(raw);
        Validator validator = new Validator();
        String reason = text(body, "reason", "");
        validator.check(Set.of("spam", "offensive", "broken-link", "other").contains(reason),
                "reason", "validation.report.reason.invalid");
        String comment = nullableText(body, "comment");
        validator.check(length(comment) <= 1000, "comment", "validation.report.comment.too-long");
        validator.throwIfInvalid();
        return new ReportInput(reason, comment);
    }

    private ResolutionInput validateResolutionInput(JsonNode raw) {
        JsonNode body = objectBody(raw);
        Validator validator = new Validator();
        String resolution = text(body, "resolution", "");
        validator.check(validReportStatus(resolution), "resolution", "validation.resolution.invalid");
        String note = nullableText(body, "note");
        validator.check(length(note) <= 1000, "note", "validation.resolution.note.too-long");
        validator.throwIfInvalid();
        return new ResolutionInput(resolution, note);
    }

    private BookmarkStatusInput validateBookmarkStatusInput(JsonNode raw) {
        JsonNode body = objectBody(raw);
        Validator validator = new Validator();
        String status = text(body, "status", "");
        validator.check(Set.of("active", "hidden").contains(status), "status", "validation.bookmark-status.invalid");
        String note = nullableText(body, "note");
        validator.check(length(note) <= 1000, "note", "validation.bookmark-status.note.too-long");
        validator.throwIfInvalid();
        return new BookmarkStatusInput(status, note);
    }

    private UserStatusInput validateUserStatusInput(JsonNode raw) {
        JsonNode body = objectBody(raw);
        String status = text(body, "status", "");
        if (!Set.of("active", "blocked").contains(status)) {
            throw StackverseProblem.badRequest("status is required");
        }
        String reason = Optional.ofNullable(nullableText(body, "reason")).map(String::trim).orElse(null);
        return new UserStatusInput(status, reason);
    }

    private Report reopenReport(Connection connection, Caller caller, Report report, List<Runnable> events) {
        boolean duplicate = scalarLong(connection,
                "select count(*) from reports where bookmark_id = ? and reporter = ? and status = 'open' and id <> ?",
                List.of(report.bookmarkId(), report.reporter(), report.id())) > 0;
        if (duplicate) {
            throw StackverseProblem.conflict("The reporter already has another open report on this bookmark.");
        }
        Report reopened;
        try {
            reopened = queryOne(connection,
                    "update reports set status = 'open', resolved_by = null, resolved_at = null, resolution_note = null"
                            + " where id = ? returning *",
                    List.of(report.id()),
                    StackverseService::report).orElseThrow();
        } catch (RuntimeException error) {
            if (isUniqueViolation(error)) {
                throw StackverseProblem.conflict("The reporter already has another open report on this bookmark.");
            }
            throw error;
        }
        recordAudit(connection, caller.username(), "report.reopened", "report", report.id().toString(),
                Map.of("bookmarkId", report.bookmarkId().toString()));
        events.add(() -> StackverseLog.event(LOG, Logger.Level.INFO, "report_reopened", "success", "Report re-opened",
                Map.of("actor", caller.username(), "resource_type", "report", "resource_id", report.id().toString(),
                        "bookmark_id", report.bookmarkId().toString())));
        return reopened;
    }

    private Report resolveOne(Connection connection, Caller caller, Report report, String resolution, String note,
                              boolean autoResolved, List<Runnable> events) {
        Instant resolvedAt = now();
        Report updated = queryOne(connection,
                "update reports set status = ?, resolved_by = ?, resolved_at = ?, resolution_note = ?"
                        + " where id = ? returning *",
                params(resolution, caller.username(), resolvedAt, note, report.id()),
                StackverseService::report).orElseThrow();
        recordAudit(connection, caller.username(), "report.resolved", "report", report.id().toString(),
                detail("bookmarkId", report.bookmarkId().toString(),
                        "resolution", resolution,
                        "note", note,
                        "autoResolved", autoResolved));
        events.add(() -> StackverseLog.event(LOG, Logger.Level.INFO, "report_resolved", "success", "Report resolved",
                Map.of("actor", caller.username(), "resource_type", "report", "resource_id", report.id().toString(),
                        "bookmark_id", report.bookmarkId().toString(),
                        "resolution", resolution,
                        "auto_resolved", autoResolved)));
        return updated;
    }

    private void hideBookmark(Connection connection, Caller caller, UUID bookmarkId, String note, List<Runnable> events) {
        Bookmark bookmark = queryOne(connection, "select * from bookmarks where id = ?", List.of(bookmarkId),
                StackverseService::bookmark).orElseThrow(StackverseProblem::notFound);
        if ("hidden".equals(bookmark.status())) {
            return;
        }
        execute(connection, "update bookmarks set status = 'hidden', updated_at = ? where id = ?",
                List.of(now(), bookmarkId));
        recordAudit(connection, caller.username(), "bookmark.status-changed", "bookmark", bookmarkId.toString(),
                detail("from", bookmark.status(), "to", "hidden", "note", note));
        events.add(() -> StackverseLog.event(LOG, Logger.Level.INFO, "bookmark_status_changed", "success",
                "Bookmark hidden by an actioned report",
                Map.of("actor", caller.username(), "resource_type", "bookmark", "resource_id", bookmarkId.toString(),
                        "from", bookmark.status(), "to", "hidden")));
    }

    private Report ownReportForUpdate(Connection connection, String reporter, UUID id) {
        Report report = queryOne(connection, "select * from reports where id = ? for update", List.of(id),
                StackverseService::report).orElseThrow(StackverseProblem::notFound);
        if (!report.reporter().equals(reporter)) {
            throw StackverseProblem.notFound();
        }
        return report;
    }

    private Caller currentCaller() {
        return AuthSupport.currentCaller(securityIdentity, jwt);
    }

    private Caller requireCaller() {
        Caller caller = currentCaller();
        if (caller == null) {
            throw StackverseProblem.unauthorized("Authentication is required.");
        }
        return caller;
    }

    private Caller requireRole(String role) {
        Caller caller = requireCaller();
        if (!caller.roles().contains(role)) {
            StackverseLog.event(LOG, Logger.Level.INFO, "authz_denied", "denied",
                    "Denied a request lacking the required role", Map.of("actor", caller.username()));
            throw StackverseProblem.forbidden("You do not have the role required for this operation.");
        }
        return caller;
    }

    private Response etag(RequestContext request, Object payload, Map<String, String> extraHeaders) {
        try {
            String body = mapper.writeValueAsString(payload);
            String etag = "\"" + Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(MessageDigest.getInstance("SHA-256").digest(body.getBytes(java.nio.charset.StandardCharsets.UTF_8)))
                    + "\"";
            Response.ResponseBuilder builder = ifNoneMatch(request, etag)
                    ? Response.status(Response.Status.NOT_MODIFIED)
                    : Response.ok(body, MediaType.APPLICATION_JSON_TYPE);
            builder.header("ETag", etag).header("Cache-Control", "no-cache");
            if (extraHeaders != null) {
                extraHeaders.forEach(builder::header);
            }
            return builder.build();
        } catch (Exception error) {
            throw new IllegalStateException(error);
        }
    }

    private boolean ifNoneMatch(RequestContext request, String etag) {
        String raw = request.headers().getHeaderString(HttpHeaders.IF_NONE_MATCH);
        if (raw == null) {
            return false;
        }
        for (String candidate : raw.split(",")) {
            if (candidate.trim().equals(etag)) {
                return true;
            }
        }
        return false;
    }

    private int pagingPage(RequestContext request) {
        int page = intParam(request, "page", 0);
        if (page < 0) {
            throw StackverseProblem.badRequest("page must not be negative");
        }
        return page;
    }

    private int pageSize(RequestContext request) {
        int size = intParam(request, "size", 20);
        if (size < 1 || size > 100) {
            throw StackverseProblem.badRequest("size must be between 1 and 100");
        }
        return size;
    }

    private long offset(int page, int size) {
        return Math.multiplyFull(page, size);
    }

    private int intParam(RequestContext request, String name, int fallback) {
        String value = singleParam(request, name);
        if (value == null || value.isBlank()) {
            return fallback;
        }
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException error) {
            throw StackverseProblem.badRequest(name + " must be an integer");
        }
    }

    private void equalFilter(RequestContext request, SqlWhere where, String column, String parameter) {
        String value = singleParam(request, parameter);
        if (value != null) {
            where.and(column + " = ?", value);
        }
    }

    private Instant timeParam(RequestContext request, String name) {
        String value = singleParam(request, name);
        if (value == null) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException error) {
            throw StackverseProblem.badRequest(name + " must be an RFC 3339 timestamp");
        }
    }

    private String singleParam(RequestContext request, String name) {
        List<String> values = queryParams(request).get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw StackverseProblem.badRequest(name + " must not be repeated");
        }
        return values.get(0);
    }

    private MultivaluedMap<String, String> queryParams(RequestContext request) {
        return request.uriInfo().getQueryParameters();
    }

    private <T> T withConnection(SqlFunction<Connection, T> function) {
        try (Connection connection = dataSource.getConnection()) {
            return function.apply(connection);
        } catch (SQLException error) {
            throw new DbException(error);
        }
    }

    private <T> T inTransaction(SqlFunction<Connection, T> function) {
        return withConnection(connection -> {
            boolean previousAutoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = function.apply(connection);
                connection.commit();
                return result;
            } catch (SQLException | RuntimeException | Error error) {
                try {
                    connection.rollback();
                } catch (SQLException rollbackError) {
                    error.addSuppressed(rollbackError);
                }
                throw error;
            } finally {
                connection.setAutoCommit(previousAutoCommit);
            }
        });
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

    static Message message(ResultSet rs) throws SQLException {
        return new Message(
                (UUID) rs.getObject("id"),
                rs.getString("key"),
                rs.getString("language"),
                rs.getString("text"),
                rs.getString("description"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    static Report report(ResultSet rs) throws SQLException {
        return new Report(
                (UUID) rs.getObject("id"),
                (UUID) rs.getObject("bookmark_id"),
                rs.getString("reporter"),
                rs.getString("reason"),
                rs.getString("comment"),
                rs.getString("status"),
                rs.getString("resolved_by"),
                nullableInstant(rs, "resolved_at"),
                rs.getString("resolution_note"),
                instant(rs, "created_at"));
    }

    static UserAccount userAccount(ResultSet rs) throws SQLException {
        return new UserAccount(
                rs.getString("username"),
                instant(rs, "first_seen"),
                instant(rs, "last_seen"),
                rs.getString("status"),
                rs.getString("blocked_reason"),
                rs.getLong("bookmark_count"));
    }

    static AuditEntry audit(ResultSet rs) throws SQLException {
        return new AuditEntry(
                (UUID) rs.getObject("id"),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                rs.getString("detail"),
                instant(rs, "created_at"));
    }

    static Map<String, Object> bookmarkResponse(Bookmark bookmark) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", bookmark.id().toString());
        body.put("url", bookmark.url());
        body.put("title", bookmark.title());
        putIfPresent(body, "notes", bookmark.notes());
        List<String> tags = new ArrayList<>(bookmark.tags());
        tags.sort(Comparator.naturalOrder());
        body.put("tags", tags);
        body.put("visibility", bookmark.visibility());
        body.put("status", bookmark.status());
        body.put("owner", bookmark.owner());
        body.put("createdAt", iso(bookmark.createdAt()));
        body.put("updatedAt", iso(bookmark.updatedAt()));
        return body;
    }

    static Map<String, Object> messageResponse(Message message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", message.id().toString());
        body.put("key", message.key());
        body.put("language", message.language());
        body.put("text", message.text());
        putIfPresent(body, "description", message.description());
        body.put("createdAt", iso(message.createdAt()));
        body.put("updatedAt", iso(message.updatedAt()));
        return body;
    }

    static Map<String, Object> reportResponse(Report report) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", report.id().toString());
        body.put("bookmarkId", report.bookmarkId().toString());
        body.put("reporter", report.reporter());
        body.put("reason", report.reason());
        putIfPresent(body, "comment", report.comment());
        body.put("status", report.status());
        body.put("createdAt", iso(report.createdAt()));
        putIfPresent(body, "resolvedBy", report.resolvedBy());
        if (report.resolvedAt() != null) {
            body.put("resolvedAt", iso(report.resolvedAt()));
        }
        putIfPresent(body, "resolutionNote", report.resolutionNote());
        return body;
    }

    static Map<String, Object> userAccountResponse(UserAccount account) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("username", account.username());
        body.put("firstSeen", iso(account.firstSeen()));
        body.put("lastSeen", iso(account.lastSeen()));
        body.put("status", account.status());
        putIfPresent(body, "blockedReason", account.blockedReason());
        body.put("bookmarkCount", account.bookmarkCount());
        return body;
    }

    private Map<String, Object> auditResponse(AuditEntry entry) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("id", entry.id().toString());
        body.put("actor", entry.actor());
        body.put("action", entry.action());
        body.put("targetType", entry.targetType());
        body.put("targetId", entry.targetId());
        if (entry.detail() != null) {
            try {
                body.put("detail", mapper.readValue(entry.detail(), Map.class));
            } catch (JsonProcessingException error) {
                body.put("detail", Map.of());
            }
        }
        body.put("createdAt", iso(entry.createdAt()));
        return body;
    }

    private Optional<Bookmark> findBookmark(Connection connection, UUID id) {
        return queryOne(connection, "select * from bookmarks where id = ?", List.of(id), StackverseService::bookmark);
    }

    private Optional<UserAccount> findUserAccount(Connection connection, String username) {
        return queryOne(connection, userAccountSelect() + " where u.username = ?", List.of(username),
                StackverseService::userAccount);
    }

    private static String userAccountSelect() {
        return "select u.username, u.first_seen, u.last_seen, u.status, u.blocked_reason,"
                + " (select count(*) from bookmarks b where b.owner = u.username) as bookmark_count"
                + " from user_accounts u";
    }

    private boolean messageConflict(Connection connection, String key, String language, UUID excluding) {
        if (excluding == null) {
            return scalarLong(connection,
                    "select count(*) from messages where key = ? and language = ?",
                    List.of(key, language)) > 0;
        }
        return scalarLong(connection,
                "select count(*) from messages where key = ? and language = ? and id <> ?",
                List.of(key, language, excluding)) > 0;
    }

    private StackverseProblem duplicateMessage(MessageInput input) {
        return StackverseProblem.conflict("A message with key '" + input.key()
                + "' and language '" + input.language() + "' already exists.");
    }

    private void recordAudit(Connection connection, String actor, String action, String targetType,
                             String targetId, Map<String, Object> detail) {
        String detailJson;
        try {
            detailJson = detail == null ? null : mapper.writeValueAsString(detail);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
        execute(connection,
                "insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at)"
                        + " values (?, ?, ?, ?, ?, cast(? as jsonb), ?)",
                params(UUID.randomUUID(), actor, action, targetType, targetId, detailJson, now()));
    }

    private Map<String, Object> snapshot(Message message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", message.key());
        body.put("language", message.language());
        body.put("text", message.text());
        body.put("description", message.description());
        return body;
    }

    private Map<LocalDate, Long> countPerDay(Connection connection, String table, String column, LocalDate from) {
        List<DayCount> rows = query(connection,
                "select (" + column + " at time zone 'UTC')::date as day, count(*) as count"
                        + " from " + table + " where " + column + " >= ? group by day",
                List.of(from),
                rs -> new DayCount(rs.getDate("day").toLocalDate(), rs.getLong("count")));
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        for (DayCount row : rows) {
            counts.put(row.date(), row.count());
        }
        return counts;
    }

    private static Map<String, Object> pageResponse(Object items, int page, int size, long totalItems) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("items", items);
        body.put("page", page);
        body.put("size", size);
        body.put("totalItems", totalItems);
        body.put("totalPages", (int) Math.ceil(totalItems / (double) size));
        return body;
    }

    private static JsonNode objectBody(JsonNode raw) {
        return raw != null && raw.isObject() ? raw : com.fasterxml.jackson.databind.node.JsonNodeFactory.instance.objectNode();
    }

    private static String text(JsonNode body, String field, String fallback) {
        JsonNode value = body.get(field);
        return value != null && value.isTextual() ? value.asText() : fallback;
    }

    private static String nullableText(JsonNode body, String field) {
        JsonNode value = body.get(field);
        return value != null && value.isTextual() ? value.asText() : null;
    }

    private static List<String> normalizeBodyTags(JsonNode tagsNode) {
        if (tagsNode == null || !tagsNode.isArray()) {
            return List.of();
        }
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        tagsNode.forEach(tag -> tags.add(tag.asText("").trim().toLowerCase(Locale.ROOT)));
        return new ArrayList<>(tags);
    }

    private static List<String> normalizeQueryTags(List<String> rawTags) {
        List<String> tags = rawTags.stream()
                .map(tag -> tag.trim().toLowerCase(Locale.ROOT))
                .toList();
        Validator validator = new Validator();
        validator.check(tags.stream().allMatch(tag -> TAG_PATTERN.matcher(tag).matches()),
                "tag", "validation.tag.invalid");
        validator.throwIfInvalid();
        return tags;
    }

    private static boolean isHttpUrl(String raw) {
        try {
            URI uri = URI.create(raw);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null
                    && !uri.getHost().isBlank();
        } catch (IllegalArgumentException error) {
            return false;
        }
    }

    private static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException error) {
            throw StackverseProblem.notFound();
        }
    }

    private static boolean validReportStatus(String status) {
        return Set.of("open", "dismissed", "actioned").contains(status);
    }

    private static void maxLength(String value, int max, String field) {
        if (value != null && length(value) > max) {
            throw StackverseProblem.badRequest(field + " must be at most " + max + " characters");
        }
    }

    private static int length(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    private static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static String iso(Instant instant) {
        return instant.toString();
    }

    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Timestamp.class).toInstant();
    }

    private static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getObject(column, Timestamp.class);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static List<String> textArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        String[] values = (String[]) array.getArray();
        return List.of(values);
    }

    static <T> Optional<T> queryOne(Connection connection, String sql, List<?> params, RowMapper<T> mapper) {
        List<T> rows = query(connection, sql, params, mapper);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }

    static <T> List<T> query(Connection connection, String sql, List<?> params, RowMapper<T> mapper) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(connection, statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        } catch (SQLException error) {
            throw new DbException(error);
        }
    }

    static void execute(Connection connection, String sql, List<?> params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(connection, statement, params);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new DbException(error);
        }
    }

    static long scalarLong(Connection connection, String sql, List<?> params) {
        return queryOne(connection, sql, params, rs -> rs.getLong(1)).orElse(0L);
    }

    private static void bind(Connection connection, PreparedStatement statement, List<?> params) throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            int index = i + 1;
            if (value instanceof List<?> list) {
                String[] array = list.stream().map(String::valueOf).toArray(String[]::new);
                statement.setArray(index, connection.createArrayOf("text", array));
            } else if (value instanceof Instant instant) {
                statement.setTimestamp(index, Timestamp.from(instant));
            } else if (value instanceof LocalDate date) {
                statement.setDate(index, Date.valueOf(date));
            } else {
                statement.setObject(index, value);
            }
        }
    }

    static boolean isUniqueViolation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DbException dbException && dbException.getCause() != null) {
                current = dbException.getCause();
                continue;
            }
            if (current instanceof PSQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            if (current instanceof SQLException sqlException && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static void putIfPresent(Map<String, Object> body, String key, Object value) {
        if (value != null) {
            body.put(key, value);
        }
    }

    static Response v1BookmarksDeprecationHeaders(Response response) {
        return Response.fromResponse(response)
                .header("Deprecation", V1_BOOKMARKS_DEPRECATION)
                .header("Sunset", V1_BOOKMARKS_SUNSET)
                .header("Link", V1_BOOKMARKS_SUCCESSOR)
                .build();
    }

    static List<Object> params(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    static Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            body.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return body;
    }
}
