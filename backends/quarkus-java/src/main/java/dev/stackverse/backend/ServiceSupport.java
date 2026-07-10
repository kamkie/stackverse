package dev.stackverse.backend;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import java.nio.charset.StandardCharsets;
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
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;
import org.postgresql.util.PSQLException;

abstract class ServiceSupport {
    protected static final Logger LOG = Logger.getLogger(ServiceSupport.class);

    protected static final String V1_BOOKMARKS_DEPRECATION = "@1782864000";
    protected static final String V1_BOOKMARKS_SUNSET = "Thu, 01 Jul 2027 00:00:00 GMT";
    protected static final String V1_BOOKMARKS_SUCCESSOR =
            "</api/v2/bookmarks>; rel=\"successor-version\"";

    protected static final Pattern TAG_PATTERN = Pattern.compile("^[a-z0-9-]{1,30}$");

    protected final DataSource dataSource;
    protected final JsonWebToken jwt;
    protected final SecurityIdentity securityIdentity;
    protected final ObjectMapper mapper;
    protected final Localizer localizer;

    protected ServiceSupport() {
        this(null, null, null, null, null);
    }

    @Inject
    protected ServiceSupport(
            DataSource dataSource,
            JsonWebToken jwt,
            SecurityIdentity securityIdentity,
            ObjectMapper mapper,
            Localizer localizer) {
        this.dataSource = dataSource;
        this.jwt = jwt;
        this.securityIdentity = securityIdentity;
        this.mapper = mapper;
        this.localizer = localizer;
    }

    protected SqlWhere parseBookmarkListQuery(RequestContext request) {
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

    protected Report ownReportForUpdate(Connection connection, String reporter, UUID id) {
        Report report =
                queryOne(
                                connection,
                                "select * from reports where id = ? for update",
                                List.of(id),
                                ServiceSupport::report)
                        .orElseThrow(StackverseProblem::notFound);
        if (!report.reporter().equals(reporter)) {
            throw StackverseProblem.notFound();
        }
        return report;
    }

    protected Caller currentCaller() {
        return AuthSupport.currentCaller(securityIdentity, jwt);
    }

    protected Caller requireCaller() {
        Caller caller = currentCaller();
        if (caller == null) {
            throw StackverseProblem.unauthorized("Authentication is required.");
        }
        return caller;
    }

    protected Caller requireRole(String role) {
        Caller caller = requireCaller();
        if (!caller.roles().contains(role)) {
            StackverseLog.event(
                    LOG,
                    Logger.Level.INFO,
                    "authz_denied",
                    "denied",
                    "Denied a request lacking the required role",
                    Map.of("actor", caller.username()));
            throw StackverseProblem.forbidden(
                    "You do not have the role required for this operation.");
        }
        return caller;
    }

    protected Response etag(
            RequestContext request, Object payload, Map<String, String> extraHeaders) {
        try {
            String body = mapper.writeValueAsString(payload);
            String etag =
                    "\""
                            + Base64.getUrlEncoder()
                                    .withoutPadding()
                                    .encodeToString(
                                            MessageDigest.getInstance("SHA-256")
                                                    .digest(body.getBytes(StandardCharsets.UTF_8)))
                            + "\"";
            Response.ResponseBuilder builder =
                    ifNoneMatch(request, etag)
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

    protected boolean ifNoneMatch(RequestContext request, String etag) {
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

    protected int pagingPage(RequestContext request) {
        int page = intParam(request, "page", 0);
        if (page < 0) {
            throw StackverseProblem.badRequest("page must not be negative");
        }
        return page;
    }

    protected int pageSize(RequestContext request) {
        int size = intParam(request, "size", 20);
        if (size < 1 || size > 100) {
            throw StackverseProblem.badRequest("size must be between 1 and 100");
        }
        return size;
    }

    protected long offset(int page, int size) {
        return Math.multiplyFull(page, size);
    }

    protected int intParam(RequestContext request, String name, int fallback) {
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

    protected void equalFilter(
            RequestContext request, SqlWhere where, String column, String parameter) {
        String value = singleParam(request, parameter);
        if (value != null) {
            where.and(column + " = ?", value);
        }
    }

    protected Instant timeParam(RequestContext request, String name) {
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

    protected String singleParam(RequestContext request, String name) {
        List<String> values = queryParams(request).get(name);
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() > 1) {
            throw StackverseProblem.badRequest(name + " must not be repeated");
        }
        return values.get(0);
    }

    protected MultivaluedMap<String, String> queryParams(RequestContext request) {
        return request.uriInfo().getQueryParameters();
    }

    protected <T> T withConnection(SqlFunction<Connection, T> function) {
        try (Connection connection = dataSource.getConnection()) {
            return function.apply(connection);
        } catch (SQLException error) {
            throw new DbException(error);
        }
    }

    protected <T> T inTransaction(SqlFunction<Connection, T> function) {
        return withConnection(
                connection -> {
                    boolean previousAutoCommit = connection.getAutoCommit();
                    connection.setAutoCommit(false);
                    Throwable failure = null;
                    try {
                        T result = function.apply(connection);
                        connection.commit();
                        return result;
                    } catch (SQLException | RuntimeException | Error error) {
                        failure = error;
                        try {
                            connection.rollback();
                        } catch (SQLException rollbackError) {
                            error.addSuppressed(rollbackError);
                        }
                        throw error;
                    } finally {
                        try {
                            connection.setAutoCommit(previousAutoCommit);
                        } catch (SQLException restoreError) {
                            if (failure != null) {
                                failure.addSuppressed(restoreError);
                            } else {
                                LOG.warn(
                                        "Failed to restore connection auto-commit after transaction",
                                        restoreError);
                            }
                        }
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

    static MessageResponse messageResponse(Message message) {
        return new MessageResponse(
                message.id(),
                message.key(),
                message.language(),
                message.text(),
                message.description(),
                message.createdAt(),
                message.updatedAt());
    }

    static ReportResponse reportResponse(Report report) {
        return new ReportResponse(
                report.id(),
                report.bookmarkId(),
                report.reporter(),
                report.reason(),
                report.comment(),
                report.status(),
                report.createdAt(),
                report.resolvedBy(),
                report.resolvedAt(),
                report.resolutionNote());
    }

    static UserAccountResponse userAccountResponse(UserAccount account) {
        return new UserAccountResponse(
                account.username(),
                account.firstSeen(),
                account.lastSeen(),
                account.status(),
                account.blockedReason(),
                account.bookmarkCount());
    }

    @SuppressWarnings("unchecked")
    protected AuditResponse auditResponse(AuditEntry entry) {
        Map<String, Object> detail = null;
        if (entry.detail() != null) {
            try {
                detail = mapper.readValue(entry.detail(), Map.class);
            } catch (JsonProcessingException error) {
                detail = Map.of();
            }
        }
        return new AuditResponse(
                entry.id(),
                entry.actor(),
                entry.action(),
                entry.targetType(),
                entry.targetId(),
                detail,
                entry.createdAt());
    }

    protected Optional<Bookmark> findBookmark(Connection connection, UUID id) {
        return queryOne(
                connection,
                "select * from bookmarks where id = ?",
                List.of(id),
                ServiceSupport::bookmark);
    }

    protected Optional<UserAccount> findUserAccount(Connection connection, String username) {
        return queryOne(
                connection,
                userAccountSelect() + " where u.username = ?",
                List.of(username),
                ServiceSupport::userAccount);
    }

    protected static String userAccountSelect() {
        return "select u.username, u.first_seen, u.last_seen, u.status, u.blocked_reason,"
                + " (select count(*) from bookmarks b where b.owner = u.username) as bookmark_count"
                + " from user_accounts u";
    }

    protected boolean messageConflict(
            Connection connection, String key, String language, UUID excluding) {
        if (excluding == null) {
            return scalarLong(
                            connection,
                            "select count(*) from messages where key = ? and language = ?",
                            List.of(key, language))
                    > 0;
        }
        return scalarLong(
                        connection,
                        "select count(*) from messages where key = ? and language = ? and id <> ?",
                        List.of(key, language, excluding))
                > 0;
    }

    protected StackverseProblem duplicateMessage(MessageInput input) {
        return StackverseProblem.conflict(
                "A message with key '"
                        + input.key()
                        + "' and language '"
                        + input.language()
                        + "' already exists.");
    }

    protected void recordAudit(
            Connection connection,
            String actor,
            String action,
            String targetType,
            String targetId,
            Map<String, Object> detail) {
        String detailJson;
        try {
            detailJson = detail == null ? null : mapper.writeValueAsString(detail);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
        execute(
                connection,
                "insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at)"
                        + " values (?, ?, ?, ?, ?, cast(? as jsonb), ?)",
                params(UUID.randomUUID(), actor, action, targetType, targetId, detailJson, now()));
    }

    protected Map<String, Object> snapshot(Message message) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("key", message.key());
        body.put("language", message.language());
        body.put("text", message.text());
        body.put("description", message.description());
        return body;
    }

    protected Map<LocalDate, Long> countPerDay(
            Connection connection, String table, String column, LocalDate from) {
        List<DayCount> rows =
                query(
                        connection,
                        "select ("
                                + column
                                + " at time zone 'UTC')::date as day, count(*) as count"
                                + " from "
                                + table
                                + " where "
                                + column
                                + " >= ? group by day",
                        List.of(from),
                        rs -> new DayCount(rs.getDate("day").toLocalDate(), rs.getLong("count")));
        Map<LocalDate, Long> counts = new LinkedHashMap<>();
        for (DayCount row : rows) {
            counts.put(row.date(), row.count());
        }
        return counts;
    }

    protected static <T> PageResponse<T> pageResponse(
            List<T> items, int page, int size, long totalItems) {
        return new PageResponse<>(
                items, page, size, totalItems, (int) Math.ceil(totalItems / (double) size));
    }

    protected static List<String> normalizeQueryTags(List<String> rawTags) {
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

    protected static UUID parseUuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException error) {
            throw StackverseProblem.notFound();
        }
    }

    protected static boolean validReportStatus(String status) {
        return Set.of("open", "dismissed", "actioned").contains(status);
    }

    protected static void maxLength(String value, int max, String field) {
        if (value != null && length(value) > max) {
            throw StackverseProblem.badRequest(field + " must be at most " + max + " characters");
        }
    }

    protected static int length(String value) {
        return value == null ? 0 : value.codePointCount(0, value.length());
    }

    protected static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    protected static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Timestamp.class).toInstant();
    }

    protected static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getObject(column, Timestamp.class);
        return timestamp == null ? null : timestamp.toInstant();
    }

    protected static List<String> textArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        String[] values = (String[]) array.getArray();
        return List.of(values);
    }

    static <T> Optional<T> queryOne(
            Connection connection, String sql, List<?> params, RowMapper<T> mapper) {
        List<T> rows = query(connection, sql, params, mapper);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(rows.get(0));
    }

    static <T> List<T> query(
            Connection connection, String sql, List<?> params, RowMapper<T> mapper) {
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

    protected static void bind(Connection connection, PreparedStatement statement, List<?> params)
            throws SQLException {
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
            if (current instanceof PSQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            if (current instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
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
