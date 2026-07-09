package dev.stackverse.openliberty;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

abstract class ResourceSupport {
    private static final Pattern TAG_PATTERN = Pattern.compile("^[a-z0-9-]{1,30}$");
    private static final Set<String> VISIBILITIES = Set.of("private", "public");
    private static final Set<String> REPORT_STATUSES = Set.of("open", "dismissed", "actioned");

    @Inject protected JdbcRepository runtime;

    @Inject protected MessageCatalog messages;

    @Inject protected EventLogger log;

    @Inject protected jakarta.validation.Validator beanValidator;

    @Context protected HttpServletRequest request;

    @Context protected UriInfo uriInfo;

    @Context protected HttpHeaders headers;

    protected Caller caller() {
        return (Caller) request.getAttribute(AuthFilter.CALLER_ATTRIBUTE);
    }

    protected Caller requireCaller() {
        Caller caller = caller();
        if (caller == null) throw ApiProblem.unauthorized();
        return caller;
    }

    protected Caller requireRole(String role) {
        Caller caller = requireCaller();
        if (!caller.hasRole(role)) {
            log.event(
                    "info",
                    "authz_denied",
                    "denied",
                    "Denied a request lacking the required role",
                    Map.of("actor", caller.username()));
            throw ApiProblem.forbidden("You do not have the role required for this operation.");
        }
        return caller;
    }

    protected ListFilters listFilters() {
        String q = single("q");
        requireMax(q, 200, "q");
        String visibility = single("visibility");
        if (visibility != null && !VISIBILITIES.contains(visibility)) {
            throw ApiProblem.badRequest("unknown visibility: " + visibility);
        }
        List<String> tags =
                validateTags(uriInfo.getQueryParameters().getOrDefault("tag", List.of()), "tag");
        return new ListFilters(tags, q, visibility);
    }

    protected QueryParts listingWhere(Caller caller, ListFilters filters) {
        List<String> conditions = new ArrayList<>();
        List<Object> params = new ArrayList<>();
        if ("public".equals(filters.visibility())) {
            conditions.add("visibility = 'public' and status = 'active'");
        } else {
            if (caller == null) throw ApiProblem.unauthorized();
            conditions.add("owner = ?");
            params.add(caller.username());
            if (filters.visibility() != null) {
                conditions.add("visibility = ?");
                params.add(filters.visibility());
            }
        }
        if (!filters.tags().isEmpty()) {
            conditions.add("tags @> ?::text[]");
            params.add(filters.tags().toArray(String[]::new));
        }
        if (filters.q() != null && !filters.q().isBlank()) {
            conditions.add("(title ilike ? escape '\\' or notes ilike ? escape '\\')");
            String pattern = "%" + escapeLike(filters.q()) + "%";
            params.add(pattern);
            params.add(pattern);
        }
        return new QueryParts(String.join(" and ", conditions), params);
    }

    protected BookmarkInput bookmarkInput(BookmarkInput input) {
        BookmarkInput validated = validateDto(input);
        if (!VISIBILITIES.contains(validated.visibility())) {
            throw ApiProblem.badRequest("unknown visibility: " + validated.visibility());
        }
        return validated;
    }

    protected MessageInput messageInput(MessageInput input) {
        return validateDto(input);
    }

    protected ReportInput reportInput(ReportInput input) {
        return validateDto(input);
    }

    protected <T> T validateDto(T input) {
        if (input == null) {
            throw ApiProblem.badRequest("Malformed JSON request body.");
        }
        List<FieldViolation> violations =
                beanValidator.validate(input).stream()
                        .map(
                                violation ->
                                        new FieldViolation(
                                                topLevelField(
                                                        violation.getPropertyPath().toString()),
                                                messageKey(violation.getMessageTemplate())))
                        .distinct()
                        .sorted(
                                java.util.Comparator.comparing(FieldViolation::field)
                                        .thenComparing(FieldViolation::messageKey))
                        .toList();
        if (!violations.isEmpty()) {
            throw new ValidationProblem(violations);
        }
        return input;
    }

    private static String topLevelField(String path) {
        int dot = path.indexOf('.');
        int bracket = path.indexOf('[');
        int end = dot < 0 ? bracket : bracket < 0 ? dot : Math.min(dot, bracket);
        return end < 0 ? path : path.substring(0, end);
    }

    private static String messageKey(String template) {
        return template.startsWith("{") && template.endsWith("}")
                ? template.substring(1, template.length() - 1)
                : template;
    }

    protected ApiModels.Bookmark findBookmark(Connection connection, UUID id, boolean lock)
            throws SQLException {
        try (PreparedStatement statement =
                runtime.prepare(
                        connection,
                        "select * from bookmarks where id = ?" + (lock ? " for update" : ""),
                        id)) {
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? bookmark(rs) : null;
            }
        }
    }

    protected ApiModels.Bookmark bookmark(ResultSet rs) throws SQLException {
        return new ApiModels.Bookmark(
                rs.getObject("id").toString(),
                rs.getString("url"),
                rs.getString("title"),
                rs.getString("notes"),
                array(rs, "tags"),
                rs.getString("visibility"),
                rs.getString("status"),
                rs.getString("owner"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    protected ApiModels.Message message(ResultSet rs) throws SQLException {
        return new ApiModels.Message(
                rs.getObject("id").toString(),
                rs.getString("key"),
                rs.getString("language"),
                rs.getString("text"),
                rs.getString("description"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    protected ApiModels.Report report(ResultSet rs) throws SQLException {
        java.sql.Timestamp resolvedAt = rs.getTimestamp("resolved_at");
        return new ApiModels.Report(
                rs.getObject("id").toString(),
                rs.getObject("bookmark_id").toString(),
                rs.getString("reporter"),
                rs.getString("reason"),
                rs.getString("comment"),
                rs.getString("status"),
                instant(rs, "created_at"),
                rs.getString("resolved_by"),
                resolvedAt == null ? null : resolvedAt.toInstant().toString(),
                rs.getString("resolution_note"));
    }

    protected ApiModels.UserAccount userAccount(ResultSet rs) throws SQLException {
        return new ApiModels.UserAccount(
                rs.getString("username"),
                instant(rs, "first_seen"),
                instant(rs, "last_seen"),
                rs.getString("status"),
                rs.getString("blocked_reason"),
                rs.getLong("bookmark_count"));
    }

    protected ApiModels.AuditEntry auditEntry(ResultSet rs) throws SQLException {
        Map<String, Object> detailMap = null;
        String detailJson = rs.getString("detail");
        if (detailJson != null) {
            try {
                detailMap =
                        JsonSupport.MAPPER.readValue(
                                detailJson, new TypeReference<Map<String, Object>>() {});
            } catch (Exception ex) {
                detailMap = Map.of();
            }
        }
        return new ApiModels.AuditEntry(
                rs.getObject("id").toString(),
                rs.getString("actor"),
                rs.getString("action"),
                rs.getString("target_type"),
                rs.getString("target_id"),
                detailMap,
                instant(rs, "created_at"));
    }

    protected ApiModels.Report resolveOne(
            Connection connection,
            String actor,
            ApiModels.Report report,
            String resolution,
            String note,
            boolean autoResolved)
            throws SQLException {
        UUID id = UUID.fromString(report.id());
        try (PreparedStatement statement =
                runtime.prepare(
                        connection,
                        """
        update reports
        set status = ?, resolved_by = ?, resolved_at = ?, resolution_note = ?
        where id = ?
        returning *
        """,
                        resolution,
                        actor,
                        Instant.now(),
                        note,
                        id)) {
            try (ResultSet rs = statement.executeQuery()) {
                rs.next();
                ApiModels.Report row = report(rs);
                audit(
                        connection,
                        actor,
                        "report.resolved",
                        "report",
                        id.toString(),
                        linked(
                                "bookmarkId",
                                report.bookmarkId(),
                                "resolution",
                                resolution,
                                "note",
                                note,
                                "autoResolved",
                                autoResolved));
                return row;
            }
        }
    }

    protected void hideBookmark(Connection connection, String actor, UUID bookmarkId, String note)
            throws SQLException {
        ApiModels.Bookmark bookmark = findBookmark(connection, bookmarkId, false);
        if (bookmark == null) throw ApiProblem.notFound();
        if ("hidden".equals(bookmark.status())) return;
        try (PreparedStatement statement =
                runtime.prepare(
                        connection,
                        "update bookmarks set status = 'hidden', updated_at = ? where id = ?",
                        Instant.now(),
                        bookmarkId)) {
            statement.executeUpdate();
        }
        audit(
                connection,
                actor,
                "bookmark.status-changed",
                "bookmark",
                bookmarkId.toString(),
                linked("from", "active", "to", "hidden", "note", note));
    }

    protected ApiModels.Report ownReport(Connection connection, String reporter, UUID id)
            throws SQLException {
        ApiModels.Report report = reportById(connection, id, true);
        if (report == null || !reporter.equals(report.reporter())) throw ApiProblem.notFound();
        return report;
    }

    protected ApiModels.Report reportById(Connection connection, UUID id, boolean lock)
            throws SQLException {
        try (PreparedStatement statement =
                runtime.prepare(
                        connection,
                        "select * from reports where id = ?" + (lock ? " for update" : ""),
                        id)) {
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? report(rs) : null;
            }
        }
    }

    protected ApiModels.UserAccount findUser(String username) throws SQLException {
        try (Connection connection = runtime.connection();
                PreparedStatement statement =
                        runtime.prepare(
                                connection,
                                """
             select u.*, (select count(*)::int from bookmarks b where b.owner = u.username) as bookmark_count
             from user_accounts u
             where u.username = ?
             """,
                                username)) {
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? userAccount(rs) : null;
            }
        }
    }

    protected boolean exists(Connection connection, String sql, Object... params)
            throws SQLException {
        try (PreparedStatement statement = runtime.prepare(connection, sql, params);
                ResultSet rs = statement.executeQuery()) {
            return rs.next();
        }
    }

    protected void audit(
            Connection connection,
            String actor,
            String action,
            String targetType,
            String targetId,
            Object detail)
            throws SQLException {
        String detailJson = detail == null ? null : JsonSupport.jsonString(detail);
        try (PreparedStatement statement =
                runtime.prepare(
                        connection,
                        """
        insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at)
        values (?, ?, ?, ?, ?, ?::jsonb, ?)
        """,
                        UUID.randomUUID(),
                        actor,
                        action,
                        targetType,
                        targetId,
                        detailJson,
                        Instant.now())) {
            statement.executeUpdate();
        }
    }

    protected ResponsePage reportPage(
            String where, List<Object> params, Paging paging, String orderBy) throws SQLException {
        return queryPage("reports", where, orderBy, params, paging, this::report);
    }

    protected <T> ResponsePage queryPage(
            String table,
            String where,
            String orderBy,
            List<Object> params,
            Paging paging,
            RowMapper<T> mapper)
            throws SQLException {
        List<Object> pagedParams = append(params, paging.size(), paging.offset());
        List<T> items = new ArrayList<>();
        long total;
        try (Connection connection = runtime.connection()) {
            try (PreparedStatement statement =
                    prepare(
                            connection,
                            "select * from "
                                    + table
                                    + " where "
                                    + where
                                    + " "
                                    + orderBy
                                    + " limit ? offset ?",
                            pagedParams)) {
                try (ResultSet rs = statement.executeQuery()) {
                    while (rs.next()) items.add(mapper.map(rs));
                }
            }
            try (PreparedStatement statement =
                    prepare(
                            connection,
                            "select count(*) as count from " + table + " where " + where,
                            params)) {
                try (ResultSet rs = statement.executeQuery()) {
                    rs.next();
                    total = rs.getLong("count");
                }
            }
        }
        return new ResponsePage(items, total);
    }

    protected PreparedStatement prepare(Connection connection, String sql, List<Object> params)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < params.size(); i++)
                runtime.bind(statement, i + 1, params.get(i), connection);
            return statement;
        } catch (SQLException | RuntimeException ex) {
            try {
                statement.close();
            } catch (SQLException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    protected Paging paging() {
        int page = integer(single("page"), 0, "page");
        int size = integer(single("size"), 20, "size");
        if (page < 0) throw ApiProblem.badRequest("page must not be negative");
        if (size < 1 || size > 100) throw ApiProblem.badRequest("size must be between 1 and 100");
        return new Paging(page, size);
    }

    protected int cursorPageSize() {
        int size = integer(single("size"), 20, "size");
        if (size < 1 || size > 100) throw ApiProblem.badRequest("size must be between 1 and 100");
        return size;
    }

    protected String single(String name) {
        List<String> values = uriInfo.getQueryParameters().get(name);
        if (values == null || values.isEmpty()) return null;
        if (values.size() > 1) throw ApiProblem.badRequest(name + " must not be repeated");
        return values.get(0);
    }

    protected List<String> validateTags(List<String> raw, String field) {
        List<String> tags = raw.stream().map(tag -> tag.trim().toLowerCase()).toList();
        Validator validator = new Validator();
        validator.check(
                tags.stream().allMatch(tag -> TAG_PATTERN.matcher(tag).matches()),
                field,
                "validation.tag.invalid");
        validator.throwIfInvalid();
        return tags;
    }

    protected static String reportStatus(String value, boolean defaultOpen) {
        if (value == null) return defaultOpen ? "open" : null;
        if (!REPORT_STATUSES.contains(value))
            throw ApiProblem.badRequest("unknown status: " + value);
        return value;
    }

    protected static UUID uuid(String raw) {
        try {
            return UUID.fromString(raw);
        } catch (IllegalArgumentException ex) {
            throw ApiProblem.notFound();
        }
    }

    protected static boolean visibleTo(ApiModels.Bookmark bookmark, Caller caller) {
        return caller != null && caller.username().equals(bookmark.owner())
                || "public".equals(bookmark.visibility()) && "active".equals(bookmark.status());
    }

    protected static boolean isHttpUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null;
        } catch (IllegalArgumentException ex) {
            return false;
        }
    }

    protected static String text(JsonNode node, String field, String fallback) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? fallback : value.asText();
    }

    protected static String nullableText(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() || !value.isTextual() ? null : value.asText();
    }

    protected static int integer(String raw, int fallback, String name) {
        if (raw == null || raw.isEmpty()) return fallback;
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ex) {
            throw ApiProblem.badRequest(name + " must be an integer");
        }
    }

    protected static void requireMax(String value, int max, String name) {
        if (value != null && value.length() > max)
            throw ApiProblem.badRequest(name + " must be at most " + max + " characters");
    }

    protected static String escapeLike(String value) {
        return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    protected static ApiModels.Page page(List<?> items, Paging paging, long total) {
        long totalPages = total == 0 ? 0 : Math.ceilDiv(total, paging.size());
        return new ApiModels.Page(items, paging.page(), paging.size(), total, totalPages);
    }

    protected static List<Object> append(List<Object> values, Object... additional) {
        List<Object> result = new ArrayList<>(values);
        result.addAll(List.of(additional));
        return result;
    }

    protected static Map<String, Object> linked(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            put(result, (String) pairs[i], pairs[i + 1]);
        }
        return result;
    }

    protected static void put(Map<String, Object> map, String key, Object value) {
        if (value != null) map.put(key, value);
    }

    protected static String instant(ResultSet rs, String column) throws SQLException {
        return rs.getTimestamp(column).toInstant().toString();
    }

    protected static List<String> array(ResultSet rs, String column) throws SQLException {
        Object value = rs.getArray(column).getArray();
        if (value instanceof String[] strings) return List.of(strings);
        Object[] objects = (Object[]) value;
        List<String> result = new ArrayList<>();
        for (Object object : objects) result.add(String.valueOf(object));
        return result;
    }

    protected static Instant parseInstant(String value, String name) {
        try {
            return Instant.parse(value);
        } catch (Exception ex) {
            throw ApiProblem.badRequest(name + " must be an RFC 3339 date-time");
        }
    }

    protected long count(String sql) throws SQLException {
        try (Connection connection = runtime.connection();
                PreparedStatement statement = connection.prepareStatement(sql);
                ResultSet rs = statement.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    protected Map<String, Integer> countPerDay(String table, String column, Instant from)
            throws SQLException {
        Map<String, Integer> result = new LinkedHashMap<>();
        try (Connection connection = runtime.connection();
                PreparedStatement statement =
                        connection.prepareStatement(
                                "select to_char("
                                        + column
                                        + " at time zone 'UTC', 'YYYY-MM-DD') as date, count(*)::int as count "
                                        + "from "
                                        + table
                                        + " where "
                                        + column
                                        + " >= ? group by date")) {
            runtime.bind(statement, 1, from, connection);
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.put(rs.getString("date"), rs.getInt("count"));
            }
        }
        return result;
    }

    protected static Cursor decodeCursor(String raw) {
        try {
            String decoded =
                    new String(
                            Base64.getUrlDecoder().decode(raw),
                            java.nio.charset.StandardCharsets.UTF_8);
            int separator = decoded.indexOf('|');
            if (separator < 0) throw new IllegalArgumentException();
            return new Cursor(
                    Instant.parse(decoded.substring(0, separator)),
                    UUID.fromString(decoded.substring(separator + 1)));
        } catch (Exception ex) {
            throw ApiProblem.badRequest("The cursor is malformed or unresolvable.");
        }
    }

    protected static String encodeCursor(Instant createdAt, UUID id) {
        String raw = createdAt + "|" + id;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    protected interface RowMapper<T> {
        T map(ResultSet rs) throws SQLException;
    }
}

record Paging(int page, int size) {
    long offset() {
        return (long) page * size;
    }
}

record ListFilters(List<String> tags, String q, String visibility) {}

record QueryParts(String where, List<Object> params) {}

record Cursor(Instant createdAt, UUID id) {}

record ResponsePage(List<?> items, long total) {}
