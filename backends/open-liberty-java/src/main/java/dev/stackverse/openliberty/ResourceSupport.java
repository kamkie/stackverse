package dev.stackverse.openliberty;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

abstract class ResourceSupport {
  private static final Pattern TAG_PATTERN = Pattern.compile("^[a-z0-9-]{1,30}$");
  private static final Pattern MESSAGE_KEY_PATTERN = Pattern.compile("^[a-z0-9-]+(\\.[a-z0-9-]+)*$");
  private static final Pattern LANGUAGE_PATTERN = Pattern.compile("^[a-z]{2}$");
  private static final Set<String> VISIBILITIES = Set.of("private", "public");
  private static final Set<String> REPORT_REASONS = Set.of("spam", "offensive", "broken-link", "other");
  private static final Set<String> REPORT_STATUSES = Set.of("open", "dismissed", "actioned");

  @Context
  protected HttpServletRequest request;

  @Context
  protected UriInfo uriInfo;

  @Context
  protected HttpHeaders headers;

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
      Log.event("info", "authz_denied", "denied", "Denied a request lacking the required role",
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
    List<String> tags = validateTags(uriInfo.getQueryParameters().getOrDefault("tag", List.of()), "tag");
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

  protected BookmarkInput bookmarkInput(String body) {
    JsonNode node = JsonSupport.objectNode(body);
    Validator validator = new Validator();
    String key = "visibility";
    String url = text(node, "url", "").trim();
    validator.check(isHttpUrl(url), "url", "validation.url.invalid");
    String title = text(node, "title", "").trim();
    validator.check(!title.isEmpty(), "title", "validation.title.required");
    validator.check(title.length() <= 200, "title", "validation.title.too-long");
    String notes = nullableText(node, "notes");
    validator.check(notes == null || notes.length() <= 4000, "notes", "validation.notes.too-long");
    List<String> tags = new ArrayList<>();
    if (node.has("tags") && node.get("tags").isArray()) {
      Set<String> seen = new HashSet<>();
      for (JsonNode tagNode : node.get("tags")) {
        String tag = tagNode.asText().trim().toLowerCase();
        if (seen.add(tag)) tags.add(tag);
      }
    }
    validator.check(tags.size() <= 10, "tags", "validation.tags.too-many");
    validator.check(tags.stream().allMatch(tag -> TAG_PATTERN.matcher(tag).matches()), "tags", "validation.tag.invalid");
    String visibility = text(node, key, "private");
    if (!VISIBILITIES.contains(visibility)) throw ApiProblem.badRequest("unknown visibility: " + visibility);
    validator.throwIfInvalid();
    return new BookmarkInput(url, title, notes, tags, visibility);
  }

  protected MessageInput messageInput(String body) {
    JsonNode node = JsonSupport.objectNode(body);
    Validator validator = new Validator();
    String key = text(node, "key", "").trim();
    validator.check(MESSAGE_KEY_PATTERN.matcher(key).matches() && key.length() <= 150, "key", "validation.message.key.invalid");
    String language = text(node, "language", "").trim();
    validator.check(LANGUAGE_PATTERN.matcher(language).matches(), "language", "validation.message.language.invalid");
    String text = text(node, "text", "");
    validator.check(!text.isEmpty(), "text", "validation.message.text.required");
    validator.check(text.length() <= 2000, "text", "validation.message.text.too-long");
    String description = nullableText(node, "description");
    validator.check(description == null || description.length() <= 1000, "description", "validation.message.description.too-long");
    validator.throwIfInvalid();
    return new MessageInput(key, language, text, description);
  }

  protected ReportInput reportInput(String body) {
    JsonNode node = JsonSupport.objectNode(body);
    Validator validator = new Validator();
    String reason = text(node, "reason", null);
    validator.check(REPORT_REASONS.contains(reason), "reason", "validation.report.reason.invalid");
    String comment = nullableText(node, "comment");
    validator.check(comment == null || comment.length() <= 1000, "comment", "validation.report.comment.too-long");
    validator.throwIfInvalid();
    return new ReportInput(reason, comment);
  }

  protected Map<String, Object> findBookmark(Connection connection, UUID id, boolean lock) throws SQLException {
    try (PreparedStatement statement = RuntimeSupport.prepare(connection,
        "select * from bookmarks where id = ?" + (lock ? " for update" : ""), id)) {
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? bookmark(rs) : null;
      }
    }
  }

  protected Map<String, Object> bookmark(ResultSet rs) throws SQLException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", rs.getObject("id").toString());
    body.put("url", rs.getString("url"));
    body.put("title", rs.getString("title"));
    put(body, "notes", rs.getString("notes"));
    body.put("tags", array(rs, "tags"));
    body.put("visibility", rs.getString("visibility"));
    body.put("status", rs.getString("status"));
    body.put("owner", rs.getString("owner"));
    body.put("createdAt", instant(rs, "created_at"));
    body.put("updatedAt", instant(rs, "updated_at"));
    return body;
  }

  protected Map<String, Object> message(ResultSet rs) throws SQLException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", rs.getObject("id").toString());
    body.put("key", rs.getString("key"));
    body.put("language", rs.getString("language"));
    body.put("text", rs.getString("text"));
    put(body, "description", rs.getString("description"));
    body.put("createdAt", instant(rs, "created_at"));
    body.put("updatedAt", instant(rs, "updated_at"));
    return body;
  }

  protected Map<String, Object> report(ResultSet rs) throws SQLException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", rs.getObject("id").toString());
    body.put("bookmarkId", rs.getObject("bookmark_id").toString());
    body.put("reporter", rs.getString("reporter"));
    body.put("reason", rs.getString("reason"));
    put(body, "comment", rs.getString("comment"));
    body.put("status", rs.getString("status"));
    body.put("createdAt", instant(rs, "created_at"));
    java.sql.Timestamp resolvedAt = rs.getTimestamp("resolved_at");
    put(body, "resolvedBy", rs.getString("resolved_by"));
    if (resolvedAt != null) body.put("resolvedAt", resolvedAt.toInstant().toString());
    put(body, "resolutionNote", rs.getString("resolution_note"));
    return body;
  }

  protected Map<String, Object> userAccount(ResultSet rs) throws SQLException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", rs.getString("username"));
    body.put("firstSeen", instant(rs, "first_seen"));
    body.put("lastSeen", instant(rs, "last_seen"));
    body.put("status", rs.getString("status"));
    put(body, "blockedReason", rs.getString("blocked_reason"));
    body.put("bookmarkCount", rs.getLong("bookmark_count"));
    return body;
  }

  protected Map<String, Object> auditEntry(ResultSet rs) throws SQLException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", rs.getObject("id").toString());
    body.put("actor", rs.getString("actor"));
    body.put("action", rs.getString("action"));
    body.put("targetType", rs.getString("target_type"));
    body.put("targetId", rs.getString("target_id"));
    String detail = rs.getString("detail");
    if (detail != null) {
      try {
        body.put("detail", JsonSupport.MAPPER.readValue(detail, Map.class));
      } catch (Exception ex) {
        body.put("detail", Map.of());
      }
    }
    body.put("createdAt", instant(rs, "created_at"));
    return body;
  }

  protected Map<String, Object> resolveOne(Connection connection, String actor, Map<String, Object> report,
      String resolution, String note, boolean autoResolved) throws SQLException {
    UUID id = UUID.fromString((String) report.get("id"));
    try (PreparedStatement statement = RuntimeSupport.prepare(connection,
        """
        update reports
        set status = ?, resolved_by = ?, resolved_at = ?, resolution_note = ?
        where id = ?
        returning *
        """,
        resolution, actor, Instant.now(), note, id)) {
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        Map<String, Object> row = report(rs);
        audit(connection, actor, "report.resolved", "report", id.toString(),
            linked("bookmarkId", report.get("bookmarkId"), "resolution", resolution, "note", note, "autoResolved", autoResolved));
        return row;
      }
    }
  }

  protected void hideBookmark(Connection connection, String actor, UUID bookmarkId, String note) throws SQLException {
    Map<String, Object> bookmark = findBookmark(connection, bookmarkId, false);
    if (bookmark == null) throw ApiProblem.notFound();
    if ("hidden".equals(bookmark.get("status"))) return;
    try (PreparedStatement statement = RuntimeSupport.prepare(connection,
        "update bookmarks set status = 'hidden', updated_at = ? where id = ?", Instant.now(), bookmarkId)) {
      statement.executeUpdate();
    }
    audit(connection, actor, "bookmark.status-changed", "bookmark", bookmarkId.toString(),
        linked("from", "active", "to", "hidden", "note", note));
  }

  protected Map<String, Object> ownReport(Connection connection, String reporter, UUID id) throws SQLException {
    Map<String, Object> report = reportById(connection, id, true);
    if (report == null || !reporter.equals(report.get("reporter"))) throw ApiProblem.notFound();
    return report;
  }

  protected Map<String, Object> reportById(Connection connection, UUID id, boolean lock) throws SQLException {
    try (PreparedStatement statement = RuntimeSupport.prepare(connection,
        "select * from reports where id = ?" + (lock ? " for update" : ""), id)) {
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? report(rs) : null;
      }
    }
  }

  protected Map<String, Object> findUser(String username) throws SQLException {
    try (Connection connection = RuntimeSupport.connection();
         PreparedStatement statement = RuntimeSupport.prepare(connection,
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

  protected static boolean exists(Connection connection, String sql, Object... params) throws SQLException {
    try (PreparedStatement statement = RuntimeSupport.prepare(connection, sql, params);
         ResultSet rs = statement.executeQuery()) {
      return rs.next();
    }
  }

  protected void audit(Connection connection, String actor, String action, String targetType, String targetId, Object detail)
      throws SQLException {
    String detailJson = detail == null ? null : JsonSupport.jsonString(detail);
    try (PreparedStatement statement = RuntimeSupport.prepare(connection,
        """
        insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at)
        values (?, ?, ?, ?, ?, ?::jsonb, ?)
        """,
        UUID.randomUUID(), actor, action, targetType, targetId, detailJson, Instant.now())) {
      statement.executeUpdate();
    }
  }

  protected ResponsePage reportPage(String where, List<Object> params, Paging paging, String orderBy) throws SQLException {
    return queryPage("reports", where, orderBy, params, paging, this::report);
  }

  protected <T> ResponsePage queryPage(String table, String where, String orderBy, List<Object> params,
      Paging paging, RowMapper<T> mapper) throws SQLException {
    List<Object> pagedParams = append(params, paging.size(), paging.offset());
    List<T> items = new ArrayList<>();
    long total;
    try (Connection connection = RuntimeSupport.connection()) {
      try (PreparedStatement statement = prepare(connection,
          "select * from " + table + " where " + where + " " + orderBy + " limit ? offset ?",
          pagedParams)) {
        try (ResultSet rs = statement.executeQuery()) {
          while (rs.next()) items.add(mapper.map(rs));
        }
      }
      try (PreparedStatement statement = prepare(connection, "select count(*) as count from " + table + " where " + where, params)) {
        try (ResultSet rs = statement.executeQuery()) {
          rs.next();
          total = rs.getLong("count");
        }
      }
    }
    return new ResponsePage(items, total);
  }

  protected PreparedStatement prepare(Connection connection, String sql, List<Object> params) throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    try {
      for (int i = 0; i < params.size(); i++) RuntimeSupport.bind(statement, i + 1, params.get(i), connection);
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
    validator.check(tags.stream().allMatch(tag -> TAG_PATTERN.matcher(tag).matches()), field, "validation.tag.invalid");
    validator.throwIfInvalid();
    return tags;
  }

  protected static String reportStatus(String value, boolean defaultOpen) {
    if (value == null) return defaultOpen ? "open" : null;
    if (!REPORT_STATUSES.contains(value)) throw ApiProblem.badRequest("unknown status: " + value);
    return value;
  }

  protected static UUID uuid(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException ex) {
      throw ApiProblem.notFound();
    }
  }

  protected static boolean visibleTo(Map<String, Object> bookmark, Caller caller) {
    return caller != null && caller.username().equals(bookmark.get("owner"))
        || "public".equals(bookmark.get("visibility")) && "active".equals(bookmark.get("status"));
  }

  protected static boolean isHttpUrl(String value) {
    try {
      URI uri = URI.create(value);
      return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
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
    if (value != null && value.length() > max) throw ApiProblem.badRequest(name + " must be at most " + max + " characters");
  }

  protected static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%");
  }

  protected static Map<String, Object> page(List<?> items, Paging paging, long total) {
    long totalPages = total == 0 ? 0 : Math.ceilDiv(total, paging.size());
    return linked("items", items, "page", paging.page(), "size", paging.size(), "totalItems", total, "totalPages", totalPages);
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

  protected static long count(String sql) throws SQLException {
    try (Connection connection = RuntimeSupport.connection();
         PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet rs = statement.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  protected static Map<String, Integer> countPerDay(String table, String column, Instant from) throws SQLException {
    Map<String, Integer> result = new LinkedHashMap<>();
    try (Connection connection = RuntimeSupport.connection();
         PreparedStatement statement = connection.prepareStatement(
             "select to_char(" + column + ", 'YYYY-MM-DD') as date, count(*)::int as count "
                 + "from " + table + " where " + column + " >= ? group by date")) {
      RuntimeSupport.bind(statement, 1, from, connection);
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) result.put(rs.getString("date"), rs.getInt("count"));
      }
    }
    return result;
  }

  protected static Cursor decodeCursor(String raw) {
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(raw), java.nio.charset.StandardCharsets.UTF_8);
      int separator = decoded.indexOf('|');
      if (separator < 0) throw new IllegalArgumentException();
      return new Cursor(Instant.parse(decoded.substring(0, separator)), UUID.fromString(decoded.substring(separator + 1)));
    } catch (Exception ex) {
      throw ApiProblem.badRequest("The cursor is malformed or unresolvable.");
    }
  }

  protected static String encodeCursor(Instant createdAt, UUID id) {
    String raw = createdAt + "|" + id;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
record BookmarkInput(String url, String title, String notes, List<String> tags, String visibility) {}
record ReportInput(String reason, String comment) {}
record MessageInput(String key, String language, String text, String description) {
  Map<String, Object> toMap() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("key", key);
    result.put("language", language);
    result.put("text", text);
    if (description != null) result.put("description", description);
    return result;
  }
}
