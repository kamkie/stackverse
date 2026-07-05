package dev.stackverse.openliberty;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.MultivaluedMap;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.net.URI;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class StackverseResource {
  private static final Pattern TAG_PATTERN = Pattern.compile("^[a-z0-9-]{1,30}$");
  private static final Pattern MESSAGE_KEY_PATTERN = Pattern.compile("^[a-z0-9-]+(\\.[a-z0-9-]+)*$");
  private static final Pattern LANGUAGE_PATTERN = Pattern.compile("^[a-z]{2}$");
  private static final Set<String> VISIBILITIES = Set.of("private", "public");
  private static final Set<String> REPORT_REASONS = Set.of("spam", "offensive", "broken-link", "other");
  private static final Set<String> REPORT_STATUSES = Set.of("open", "dismissed", "actioned");
  private static final String V1_DEPRECATION = "@1782864000";
  private static final String V1_SUNSET = "Thu, 01 Jul 2027 00:00:00 GMT";
  private static final String V1_SUCCESSOR = "</api/v2/bookmarks>; rel=\"successor-version\"";

  @Context
  HttpServletRequest request;

  @Context
  UriInfo uriInfo;

  @Context
  HttpHeaders headers;

  @GET
  @Path("/healthz")
  public Response healthz() {
    return Response.ok().build();
  }

  @GET
  @Path("/readyz")
  public Response readyz() throws SQLException {
    try (Connection connection = RuntimeSupport.connection();
         PreparedStatement statement = connection.prepareStatement("select 1")) {
      statement.execute();
      return Response.ok().build();
    } catch (SQLException ex) {
      return Response.status(503).build();
    }
  }

  @GET
  @Path("/api/v1/me")
  public Response me() {
    Caller caller = requireCaller();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", caller.username());
    if (caller.name() != null) body.put("name", caller.name());
    if (caller.email() != null) body.put("email", caller.email());
    body.put("roles", caller.roles().stream().filter(role -> role.equals("admin") || role.equals("moderator")).sorted().toList());
    return JsonSupport.json(body);
  }

  @GET
  @Path("/api/v1/bookmarks")
  public Response listBookmarksV1() throws SQLException {
    Paging paging = paging();
    ListFilters filters = listFilters();
    Caller caller = caller();
    QueryParts parts = listingWhere(caller, filters);
    List<Map<String, Object>> items = new ArrayList<>();
    long total;
    try (Connection connection = RuntimeSupport.connection()) {
      try (PreparedStatement statement = prepare(connection,
          "select * from bookmarks where " + parts.where() + " order by created_at desc, id desc limit ? offset ?",
          append(parts.params(), paging.size(), paging.page() * paging.size()))) {
        try (ResultSet rs = statement.executeQuery()) {
          while (rs.next()) items.add(bookmark(rs));
        }
      }
      try (PreparedStatement statement = prepare(connection,
          "select count(*) as count from bookmarks where " + parts.where(), parts.params())) {
        try (ResultSet rs = statement.executeQuery()) {
          rs.next();
          total = rs.getLong("count");
        }
      }
    }
    Response base = JsonSupport.json(page(items, paging, total));
    return Response.fromResponse(base)
        .header("Deprecation", V1_DEPRECATION)
        .header("Sunset", V1_SUNSET)
        .header("Link", V1_SUCCESSOR)
        .build();
  }

  @GET
  @Path("/api/v2/bookmarks")
  public Response listBookmarksV2() throws SQLException {
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
    List<Map<String, Object>> fetched = new ArrayList<>();
    try (Connection connection = RuntimeSupport.connection();
         PreparedStatement statement = prepare(connection,
             "select * from bookmarks where " + parts.where() + cursorWhere
                 + " order by created_at desc, id desc limit ?",
             append(params, size + 1))) {
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) fetched.add(bookmark(rs));
      }
    }
    List<Map<String, Object>> items = fetched.size() > size ? fetched.subList(0, size) : fetched;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("items", items);
    if (fetched.size() > size && !items.isEmpty()) {
      Map<String, Object> last = items.get(items.size() - 1);
      body.put("nextCursor", encodeCursor(Instant.parse((String) last.get("createdAt")), UUID.fromString((String) last.get("id"))));
    }
    return JsonSupport.json(body);
  }

  @POST
  @Path("/api/v1/bookmarks")
  public Response createBookmark(String body) throws SQLException {
    Caller caller = requireCaller();
    BookmarkInput input = bookmarkInput(body);
    UUID id = UUID.randomUUID();
    Instant now = Instant.now();
    Map<String, Object> created;
    try (Connection connection = RuntimeSupport.connection();
         PreparedStatement statement = RuntimeSupport.prepare(connection,
             """
             insert into bookmarks (id, owner, url, title, notes, tags, visibility, status, created_at, updated_at)
             values (?, ?, ?, ?, ?, ?::text[], ?, 'active', ?, ?)
             returning *
             """,
             id, caller.username(), input.url(), input.title(), input.notes(), input.tags().toArray(String[]::new),
             input.visibility(), now, now)) {
      try (ResultSet rs = statement.executeQuery()) {
        rs.next();
        created = bookmark(rs);
      }
    }
    return JsonSupport.created("/api/v1/bookmarks/" + id, created);
  }

  @GET
  @Path("/api/v1/bookmarks/{id}")
  public Response getBookmark(@PathParam("id") String rawId) throws SQLException {
    UUID id = uuid(rawId);
    Map<String, Object> row;
    try (Connection connection = RuntimeSupport.connection()) {
      row = findBookmark(connection, id, false);
    }
    Caller caller = caller();
    if (row == null || !visibleTo(row, caller)) {
      throw ApiProblem.notFound();
    }
    return JsonSupport.json(row);
  }

  @PUT
  @Path("/api/v1/bookmarks/{id}")
  public Response updateBookmark(@PathParam("id") String rawId, String body) {
    Caller caller = requireCaller();
    UUID id = uuid(rawId);
    BookmarkInput input = bookmarkInput(body);
    Map<String, Object> updated = RuntimeSupport.transaction(connection -> {
      Map<String, Object> bookmark = findBookmark(connection, id, true);
      if (bookmark == null || !caller.username().equals(bookmark.get("owner"))) {
        throw ApiProblem.notFound();
      }
      if ("hidden".equals(bookmark.get("status")) && "public".equals(input.visibility())) {
        throw ApiProblem.conflict(
            "This bookmark was hidden by moderation and cannot be made public.",
            "error.bookmark.hidden-publish");
      }
      try (PreparedStatement statement = RuntimeSupport.prepare(connection,
          """
          update bookmarks
          set url = ?, title = ?, notes = ?, tags = ?::text[], visibility = ?, updated_at = ?
          where id = ?
          returning *
          """,
          input.url(), input.title(), input.notes(), input.tags().toArray(String[]::new), input.visibility(), Instant.now(), id)) {
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
  public Response deleteBookmark(@PathParam("id") String rawId) throws SQLException {
    Caller caller = requireCaller();
    UUID id = uuid(rawId);
    try (Connection connection = RuntimeSupport.connection()) {
      Map<String, Object> bookmark = findBookmark(connection, id, false);
      if (bookmark == null || !caller.username().equals(bookmark.get("owner"))) {
        throw ApiProblem.notFound();
      }
      try (PreparedStatement statement = RuntimeSupport.prepare(connection, "delete from bookmarks where id = ?", id)) {
        statement.executeUpdate();
      }
    }
    return Response.noContent().build();
  }

  @GET
  @Path("/api/v1/tags")
  public Response tags() throws SQLException {
    Caller caller = requireCaller();
    List<Map<String, Object>> tags = new ArrayList<>();
    try (Connection connection = RuntimeSupport.connection();
         PreparedStatement statement = RuntimeSupport.prepare(connection,
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
          tags.add(linked("tag", rs.getString("tag"), "count", rs.getInt("count")));
        }
      }
    }
    return JsonSupport.json(linked("tags", tags));
  }

  @GET
  @Path("/api/v1/messages")
  public Response listMessages() throws SQLException {
    Paging paging = paging();
    String key = single("key");
    String language = single("language");
    String q = single("q");
    requireMax(q, 200, "q");
    List<String> conditions = new ArrayList<>(List.of("true"));
    List<Object> params = new ArrayList<>();
    if (key != null) {
      conditions.add("key = ?");
      params.add(key);
    }
    if (language != null) {
      conditions.add("language = ?");
      params.add(language);
    }
    if (q != null && !q.isBlank()) {
      conditions.add("(key ilike ? escape '\\' or text ilike ? escape '\\')");
      String pattern = "%" + escapeLike(q) + "%";
      params.add(pattern);
      params.add(pattern);
    }
    String where = String.join(" and ", conditions);
    List<Map<String, Object>> items = new ArrayList<>();
    long total;
    try (Connection connection = RuntimeSupport.connection()) {
      try (PreparedStatement statement = prepare(connection,
          "select * from messages where " + where + " order by key, language limit ? offset ?",
          append(params, paging.size(), paging.page() * paging.size()))) {
        try (ResultSet rs = statement.executeQuery()) {
          while (rs.next()) items.add(message(rs));
        }
      }
      try (PreparedStatement statement = prepare(connection, "select count(*) as count from messages where " + where, params)) {
        try (ResultSet rs = statement.executeQuery()) {
          rs.next();
          total = rs.getLong("count");
        }
      }
    }
    return JsonSupport.etagResponse(headers.getHeaderString("If-None-Match"), page(items, paging, total));
  }

  @GET
  @Path("/api/v1/messages/bundle")
  public Response bundle() throws SQLException {
    String language = resolveLanguage(firstParam(uriInfo.getQueryParameters().get("lang")), headers.getHeaderString("Accept-Language"));
    Map<String, String> messages = messageBundle(language);
    Response response = JsonSupport.etagResponse(headers.getHeaderString("If-None-Match"), linked("language", language, "messages", messages));
    return Response.fromResponse(response).header("Content-Language", language).build();
  }

  @GET
  @Path("/api/v1/messages/{id}")
  public Response getMessage(@PathParam("id") String rawId) throws SQLException {
    UUID id = uuid(rawId);
    Map<String, Object> found = null;
    try (Connection connection = RuntimeSupport.connection();
         PreparedStatement statement = RuntimeSupport.prepare(connection, "select * from messages where id = ?", id)) {
      try (ResultSet rs = statement.executeQuery()) {
        if (rs.next()) found = message(rs);
      }
    }
    if (found == null) throw ApiProblem.notFound();
    return JsonSupport.etagResponse(headers.getHeaderString("If-None-Match"), found);
  }

  @POST
  @Path("/api/v1/messages")
  public Response createMessage(String body) {
    Caller caller = requireRole("admin");
    MessageInput input = messageInput(body);
    UUID id = UUID.randomUUID();
    Map<String, Object> created = RuntimeSupport.transaction(connection -> {
      try (PreparedStatement statement = RuntimeSupport.prepare(connection,
          """
          insert into messages (id, key, language, text, description, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?)
          returning *
          """,
          id, input.key(), input.language(), input.text(), input.description(), Instant.now(), Instant.now())) {
        try (ResultSet rs = statement.executeQuery()) {
          rs.next();
          Map<String, Object> row = message(rs);
          audit(connection, caller.username(), "message.created", "message", id.toString(), input.toMap());
          return row;
        }
      } catch (SQLException ex) {
        if ("23505".equals(ex.getSQLState())) throw ApiProblem.conflict("A message with this key and language already exists.");
        throw ex;
      }
    });
    Log.event("info", "message_created", "success", "Message created",
        Map.of("actor", caller.username(), "resource_type", "message", "resource_id", id.toString()));
    return JsonSupport.created("/api/v1/messages/" + id, created);
  }

  @PUT
  @Path("/api/v1/messages/{id}")
  public Response updateMessage(@PathParam("id") String rawId, String body) {
    Caller caller = requireRole("admin");
    UUID id = uuid(rawId);
    MessageInput input = messageInput(body);
    Map<String, Object> updated = RuntimeSupport.transaction(connection -> {
      if (!exists(connection, "select 1 from messages where id = ?", id)) throw ApiProblem.notFound();
      try (PreparedStatement statement = RuntimeSupport.prepare(connection,
          """
          update messages
          set key = ?, language = ?, text = ?, description = ?, updated_at = ?
          where id = ?
          returning *
          """,
          input.key(), input.language(), input.text(), input.description(), Instant.now(), id)) {
        try (ResultSet rs = statement.executeQuery()) {
          rs.next();
          Map<String, Object> row = message(rs);
          audit(connection, caller.username(), "message.updated", "message", id.toString(), input.toMap());
          return row;
        }
      } catch (SQLException ex) {
        if ("23505".equals(ex.getSQLState())) throw ApiProblem.conflict("A message with this key and language already exists.");
        throw ex;
      }
    });
    Log.event("info", "message_updated", "success", "Message updated",
        Map.of("actor", caller.username(), "resource_type", "message", "resource_id", id.toString()));
    return JsonSupport.json(updated);
  }

  @DELETE
  @Path("/api/v1/messages/{id}")
  public Response deleteMessage(@PathParam("id") String rawId) {
    Caller caller = requireRole("admin");
    UUID id = uuid(rawId);
    RuntimeSupport.transaction(connection -> {
      Map<String, Object> deleted = null;
      try (PreparedStatement statement = RuntimeSupport.prepare(connection, "delete from messages where id = ? returning *", id)) {
        try (ResultSet rs = statement.executeQuery()) {
          if (rs.next()) deleted = message(rs);
        }
      }
      if (deleted == null) throw ApiProblem.notFound();
      audit(connection, caller.username(), "message.deleted", "message", id.toString(), deleted);
      return null;
    });
    Log.event("info", "message_deleted", "success", "Message deleted",
        Map.of("actor", caller.username(), "resource_type", "message", "resource_id", id.toString()));
    return Response.noContent().build();
  }

  @POST
  @Path("/api/v1/bookmarks/{id}/reports")
  public Response createReport(@PathParam("id") String rawBookmarkId, String body) {
    Caller caller = requireCaller();
    UUID bookmarkId = uuid(rawBookmarkId);
    ReportInput input = reportInput(body);
    Map<String, Object> report = RuntimeSupport.transaction(connection -> {
      try (PreparedStatement found = RuntimeSupport.prepare(connection,
          "select visibility, status from bookmarks where id = ? for update", bookmarkId)) {
        try (ResultSet rs = found.executeQuery()) {
          if (!rs.next() || !"public".equals(rs.getString("visibility")) || !"active".equals(rs.getString("status"))) {
            throw ApiProblem.notFound();
          }
        }
      }
      if (exists(connection, "select 1 from reports where bookmark_id = ? and reporter = ? and status = 'open'", bookmarkId, caller.username())) {
        throw ApiProblem.conflict("You already have an open report on this bookmark.");
      }
      UUID id = UUID.randomUUID();
      try (PreparedStatement statement = RuntimeSupport.prepare(connection,
          """
          insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
          values (?, ?, ?, ?, ?, 'open', ?)
          returning *
          """,
          id, bookmarkId, caller.username(), input.reason(), input.comment(), Instant.now())) {
        try (ResultSet rs = statement.executeQuery()) {
          rs.next();
          return report(rs);
        }
      } catch (SQLException ex) {
        if ("23505".equals(ex.getSQLState())) throw ApiProblem.conflict("You already have an open report on this bookmark.");
        throw ex;
      }
    });
    Log.event("info", "report_created", "success", "Report created on a public bookmark",
        Map.of("actor", caller.username(), "resource_type", "report", "resource_id", report.get("id")));
    return Response.status(Response.Status.CREATED)
        .type(MediaType.APPLICATION_JSON_TYPE.withCharset("utf-8"))
        .entity(JsonSupport.jsonString(report))
        .build();
  }

  @GET
  @Path("/api/v1/reports")
  public Response myReports() throws SQLException {
    Caller caller = requireCaller();
    Paging paging = paging();
    String status = reportStatus(single("status"), false);
    List<Object> params = new ArrayList<>(List.of(caller.username()));
    String where = "reporter = ?";
    if (status != null) {
      where += " and status = ?";
      params.add(status);
    }
    return reportPage("select * from reports where " + where + " order by created_at desc, id desc limit ? offset ?",
        "select count(*) as count from reports where " + where, params, paging);
  }

  @PUT
  @Path("/api/v1/reports/{id}")
  public Response updateMyReport(@PathParam("id") String rawId, String body) {
    Caller caller = requireCaller();
    UUID id = uuid(rawId);
    ReportInput input = reportInput(body);
    Map<String, Object> updated = RuntimeSupport.transaction(connection -> {
      Map<String, Object> report = ownReport(connection, caller.username(), id);
      if (!"open".equals(report.get("status"))) throw ApiProblem.conflict("The report has already been resolved.");
      try (PreparedStatement statement = RuntimeSupport.prepare(connection,
          "update reports set reason = ?, comment = ? where id = ? returning *",
          input.reason(), input.comment(), id)) {
        try (ResultSet rs = statement.executeQuery()) {
          rs.next();
          return report(rs);
        }
      }
    });
    Log.event("info", "report_updated", "success", "Report updated by its reporter",
        Map.of("actor", caller.username(), "resource_type", "report", "resource_id", id.toString()));
    return JsonSupport.json(updated);
  }

  @DELETE
  @Path("/api/v1/reports/{id}")
  public Response withdrawReport(@PathParam("id") String rawId) {
    Caller caller = requireCaller();
    UUID id = uuid(rawId);
    RuntimeSupport.transaction(connection -> {
      Map<String, Object> report = ownReport(connection, caller.username(), id);
      if (!"open".equals(report.get("status"))) throw ApiProblem.conflict("The report has already been resolved.");
      try (PreparedStatement statement = RuntimeSupport.prepare(connection, "delete from reports where id = ?", id)) {
        statement.executeUpdate();
      }
      return null;
    });
    Log.event("info", "report_withdrawn", "success", "Report withdrawn by its reporter",
        Map.of("actor", caller.username(), "resource_type", "report", "resource_id", id.toString()));
    return Response.noContent().build();
  }

  @GET
  @Path("/api/v1/admin/reports")
  public Response adminReports() throws SQLException {
    requireRole("moderator");
    Paging paging = paging();
    String status = reportStatus(single("status"), true);
    return reportPage(
        "select * from reports where status = ? order by created_at asc, id asc limit ? offset ?",
        "select count(*) as count from reports where status = ?",
        new ArrayList<>(List.of(status)), paging);
  }

  @PUT
  @Path("/api/v1/admin/reports/{id}")
  public Response resolveReport(@PathParam("id") String rawId, String body) {
    Caller caller = requireRole("moderator");
    UUID id = uuid(rawId);
    JsonNode node = JsonSupport.objectNode(body);
    Validator validator = new Validator();
    String resolution = text(node, "resolution", null);
    validator.check(REPORT_STATUSES.contains(resolution), "resolution", "validation.resolution.invalid");
    String note = nullableText(node, "note");
    validator.check(note == null || note.length() <= 1000, "note", "validation.resolution.note.too-long");
    validator.throwIfInvalid();
    Map<String, Object> resolved = RuntimeSupport.transaction(connection -> {
      if ("actioned".equals(resolution)) {
        UUID bookmarkId = null;
        try (PreparedStatement statement = RuntimeSupport.prepare(connection, "select bookmark_id from reports where id = ?", id);
             ResultSet rs = statement.executeQuery()) {
          if (rs.next()) bookmarkId = UUID.fromString(rs.getString("bookmark_id"));
        }
        if (bookmarkId == null) throw ApiProblem.notFound();
        try (PreparedStatement lock = RuntimeSupport.prepare(connection, "select id from bookmarks where id = ? for update", bookmarkId)) {
          lock.executeQuery().close();
        }
      }
      Map<String, Object> report = reportById(connection, id, true);
      if (report == null) throw ApiProblem.notFound();
      if ("open".equals(resolution)) {
        if (exists(connection,
            "select 1 from reports where bookmark_id = ? and reporter = ? and status = 'open' and id <> ?",
            UUID.fromString((String) report.get("bookmarkId")), report.get("reporter"), id)) {
          throw ApiProblem.conflict("The reporter already has another open report on this bookmark.");
        }
        Map<String, Object> reopened;
        try (PreparedStatement statement = RuntimeSupport.prepare(connection,
            """
            update reports
            set status = 'open', resolved_by = null, resolved_at = null, resolution_note = null
            where id = ?
            returning *
            """,
            id)) {
          try (ResultSet rs = statement.executeQuery()) {
            rs.next();
            reopened = report(rs);
          }
        } catch (SQLException ex) {
          if ("23505".equals(ex.getSQLState())) throw ApiProblem.conflict("The reporter already has another open report on this bookmark.");
          throw ex;
        }
        audit(connection, caller.username(), "report.reopened", "report", id.toString(), linked("bookmarkId", report.get("bookmarkId")));
        return reopened;
      }
      Map<String, Object> primary = resolveOne(connection, caller.username(), report, resolution, note, false);
      if ("actioned".equals(resolution)) {
        UUID bookmarkId = UUID.fromString((String) report.get("bookmarkId"));
        hideBookmark(connection, caller.username(), bookmarkId, note);
        try (PreparedStatement statement = RuntimeSupport.prepare(connection,
            """
            select * from reports
            where bookmark_id = ? and status = 'open' and id <> ?
            order by id asc for update
            """,
            bookmarkId, id)) {
          try (ResultSet rs = statement.executeQuery()) {
            while (rs.next()) {
              resolveOne(connection, caller.username(), report(rs), "actioned", note, true);
            }
          }
        }
      }
      return primary;
    });
    return JsonSupport.json(resolved);
  }

  @PUT
  @Path("/api/v1/admin/bookmarks/{id}/status")
  public Response setBookmarkStatus(@PathParam("id") String rawId, String body) {
    Caller caller = requireRole("moderator");
    UUID id = uuid(rawId);
    JsonNode node = JsonSupport.objectNode(body);
    Validator validator = new Validator();
    String status = text(node, "status", null);
    validator.check("active".equals(status) || "hidden".equals(status), "status", "validation.bookmark-status.invalid");
    String note = nullableText(node, "note");
    validator.check(note == null || note.length() <= 1000, "note", "validation.bookmark-status.note.too-long");
    validator.throwIfInvalid();
    Map<String, Object> updated = RuntimeSupport.transaction(connection -> {
      Map<String, Object> bookmark = findBookmark(connection, id, true);
      if (bookmark == null) throw ApiProblem.notFound();
      try (PreparedStatement statement = RuntimeSupport.prepare(connection,
          "update bookmarks set status = ?, updated_at = ? where id = ? returning *", status, Instant.now(), id)) {
        try (ResultSet rs = statement.executeQuery()) {
          rs.next();
          Map<String, Object> row = bookmark(rs);
          audit(connection, caller.username(), "bookmark.status-changed", "bookmark", id.toString(),
              linked("from", bookmark.get("status"), "to", status, "note", note));
          return row;
        }
      }
    });
    Log.event("info", "bookmark_status_changed", "success", "Bookmark moderation status changed",
        Map.of("actor", caller.username(), "resource_type", "bookmark", "resource_id", id.toString()));
    return JsonSupport.json(updated);
  }

  @GET
  @Path("/api/v1/admin/users")
  public Response users() throws SQLException {
    requireRole("admin");
    Paging paging = paging();
    String q = single("q");
    requireMax(q, 100, "q");
    String status = single("status");
    if (status != null && !"active".equals(status) && !"blocked".equals(status)) {
      throw ApiProblem.badRequest("unknown status: " + status);
    }
    List<String> conditions = new ArrayList<>(List.of("true"));
    List<Object> params = new ArrayList<>();
    if (q != null && !q.isBlank()) {
      conditions.add("u.username ilike ? escape '\\'");
      params.add("%" + escapeLike(q) + "%");
    }
    if (status != null) {
      conditions.add("u.status = ?");
      params.add(status);
    }
    String where = String.join(" and ", conditions);
    String withCount = "select u.*, (select count(*)::int from bookmarks b where b.owner = u.username) as bookmark_count from user_accounts u";
    List<Map<String, Object>> items = new ArrayList<>();
    long total;
    try (Connection connection = RuntimeSupport.connection()) {
      try (PreparedStatement statement = prepare(connection,
          withCount + " where " + where + " order by u.last_seen desc, u.username asc limit ? offset ?",
          append(params, paging.size(), paging.page() * paging.size()))) {
        try (ResultSet rs = statement.executeQuery()) {
          while (rs.next()) items.add(userAccount(rs));
        }
      }
      try (PreparedStatement statement = prepare(connection, "select count(*) as count from user_accounts u where " + where, params)) {
        try (ResultSet rs = statement.executeQuery()) {
          rs.next();
          total = rs.getLong("count");
        }
      }
    }
    return JsonSupport.json(page(items, paging, total));
  }

  @GET
  @Path("/api/v1/admin/users/{username}")
  public Response user(@PathParam("username") String username) throws SQLException {
    requireRole("admin");
    Map<String, Object> account = findUser(username);
    if (account == null) throw ApiProblem.notFound();
    return JsonSupport.json(account);
  }

  @PUT
  @Path("/api/v1/admin/users/{username}/status")
  public Response setUserStatus(@PathParam("username") String username, String body) {
    Caller caller = requireRole("admin");
    JsonNode node = JsonSupport.objectNode(body);
    String status = text(node, "status", null);
    if (!"active".equals(status) && !"blocked".equals(status)) throw ApiProblem.badRequest("status is required");
    String reason = nullableText(node, "reason");
    if ("blocked".equals(status)) {
      Validator validator = new Validator();
      validator.check(reason != null && !reason.isBlank(), "reason", "validation.block.reason.required");
      validator.check(reason == null || reason.length() <= 1000, "reason", "validation.block.reason.too-long");
      validator.throwIfInvalid();
      if (username.equals(caller.username())) throw ApiProblem.conflict("Admins cannot block themselves.");
    }
    RuntimeSupport.transaction(connection -> {
      if (!exists(connection, "select 1 from user_accounts where username = ? for update", username)) throw ApiProblem.notFound();
      if ("blocked".equals(status)) {
        try (PreparedStatement statement = RuntimeSupport.prepare(connection,
            "update user_accounts set status = 'blocked', blocked_reason = ? where username = ?", reason, username)) {
          statement.executeUpdate();
        }
        audit(connection, caller.username(), "user.blocked", "user", username, linked("reason", reason));
      } else {
        try (PreparedStatement statement = RuntimeSupport.prepare(connection,
            "update user_accounts set status = 'active', blocked_reason = null where username = ?", username)) {
          statement.executeUpdate();
        }
        audit(connection, caller.username(), "user.unblocked", "user", username, null);
      }
      return null;
    });
    Log.event("info", "blocked".equals(status) ? "user_blocked" : "user_unblocked", "success", "User account status changed",
        Map.of("actor", caller.username(), "resource_type", "user", "resource_id", username));
    try {
      return JsonSupport.json(findUser(username));
    } catch (SQLException ex) {
      throw new RuntimeException(ex);
    }
  }

  @GET
  @Path("/api/v1/admin/audit-log")
  public Response auditLog() throws SQLException {
    requireRole("admin");
    Paging paging = paging();
    List<String> conditions = new ArrayList<>(List.of("true"));
    List<Object> params = new ArrayList<>();
    for (String[] pair : List.of(new String[] {"actor", "actor"}, new String[] {"action", "action"},
        new String[] {"targetType", "target_type"}, new String[] {"targetId", "target_id"})) {
      String value = single(pair[0]);
      if (value != null) {
        conditions.add(pair[1] + " = ?");
        params.add(value);
      }
    }
    String from = single("from");
    if (from != null) {
      conditions.add("created_at >= ?");
      params.add(parseInstant(from, "from"));
    }
    String to = single("to");
    if (to != null) {
      conditions.add("created_at <= ?");
      params.add(parseInstant(to, "to"));
    }
    String where = String.join(" and ", conditions);
    List<Map<String, Object>> items = new ArrayList<>();
    long total;
    try (Connection connection = RuntimeSupport.connection()) {
      try (PreparedStatement statement = prepare(connection,
          "select * from audit_entries where " + where + " order by created_at desc, id desc limit ? offset ?",
          append(params, paging.size(), paging.page() * paging.size()))) {
        try (ResultSet rs = statement.executeQuery()) {
          while (rs.next()) items.add(auditEntry(rs));
        }
      }
      try (PreparedStatement statement = prepare(connection, "select count(*) as count from audit_entries where " + where, params)) {
        try (ResultSet rs = statement.executeQuery()) {
          rs.next();
          total = rs.getLong("count");
        }
      }
    }
    return JsonSupport.json(page(items, paging, total));
  }

  @GET
  @Path("/api/v1/admin/stats")
  public Response stats() throws SQLException {
    requireRole("moderator");
    LocalDate today = LocalDate.now(ZoneOffset.UTC);
    LocalDate start = today.minusDays(29);
    Instant startInstant = start.atStartOfDay().toInstant(ZoneOffset.UTC);
    Map<String, Integer> bookmarkDays = countPerDay("bookmarks", "created_at", startInstant);
    Map<String, Integer> activeDays = countPerDay("user_accounts", "last_seen", startInstant);
    List<Map<String, Object>> daily = new ArrayList<>();
    for (int i = 0; i < 30; i++) {
      String date = start.plusDays(i).toString();
      daily.add(linked("date", date, "bookmarksCreated", bookmarkDays.getOrDefault(date, 0),
          "activeUsers", activeDays.getOrDefault(date, 0)));
    }
    Map<String, Object> totals = linked(
        "users", count("select count(*) from user_accounts"),
        "bookmarks", count("select count(*) from bookmarks"),
        "publicBookmarks", count("select count(*) from bookmarks where visibility = 'public'"),
        "hiddenBookmarks", count("select count(*) from bookmarks where status = 'hidden'"),
        "openReports", count("select count(*) from reports where status = 'open'"));
    List<Map<String, Object>> topTags = new ArrayList<>();
    try (Connection connection = RuntimeSupport.connection();
         PreparedStatement statement = connection.prepareStatement(
             """
             select tag, count(*)::int as count
             from bookmarks, unnest(tags) as tag
             group by tag
             order by count desc, tag asc
             limit 10
             """)) {
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) topTags.add(linked("tag", rs.getString("tag"), "count", rs.getInt("count")));
      }
    }
    return JsonSupport.etagResponse(headers.getHeaderString("If-None-Match"),
        linked("totals", totals, "daily", daily, "topTags", topTags));
  }

  static String resolveLanguage(String lang, String acceptLanguage) {
    Set<String> supported = supportedLanguages();
    if (lang != null && supported.contains(lang)) return lang;
    for (LanguagePreference preference : parseAcceptLanguage(acceptLanguage)) {
      if (supported.contains(preference.code())) return preference.code();
    }
    return "en";
  }

  static String firstParam(List<String> values) {
    return values == null || values.isEmpty() ? null : values.get(0);
  }

  static String localize(String key, String language) {
    try (Connection connection = RuntimeSupport.connection();
         PreparedStatement statement = RuntimeSupport.prepare(connection,
             """
             select text from messages
             where key = ? and language = any(?::text[])
             order by case when language = ? then 0 else 1 end
             limit 1
             """,
             key, new String[] {language, "en"}, language)) {
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? rs.getString("text") : key;
      }
    } catch (SQLException ex) {
      throw new RuntimeException(ex);
    }
  }

  private Caller caller() {
    return (Caller) request.getAttribute(AuthFilter.CALLER_ATTRIBUTE);
  }

  private Caller requireCaller() {
    Caller caller = caller();
    if (caller == null) throw ApiProblem.unauthorized();
    return caller;
  }

  private Caller requireRole(String role) {
    Caller caller = requireCaller();
    if (!caller.hasRole(role)) {
      Log.event("info", "authz_denied", "denied", "Denied a request lacking the required role",
          Map.of("actor", caller.username()));
      throw ApiProblem.forbidden("You do not have the role required for this operation.");
    }
    return caller;
  }

  private ListFilters listFilters() {
    String q = single("q");
    requireMax(q, 200, "q");
    String visibility = single("visibility");
    if (visibility != null && !VISIBILITIES.contains(visibility)) {
      throw ApiProblem.badRequest("unknown visibility: " + visibility);
    }
    List<String> tags = validateTags(uriInfo.getQueryParameters().getOrDefault("tag", List.of()), "tag");
    return new ListFilters(tags, q, visibility);
  }

  private QueryParts listingWhere(Caller caller, ListFilters filters) {
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

  private BookmarkInput bookmarkInput(String body) {
    JsonNode node = JsonSupport.objectNode(body);
    Validator validator = new Validator();
    String url = text(node, "url", "").trim();
    if (url.isEmpty()) validator.reject("url", "validation.url.required");
    else validator.check(url.length() <= 2000 && isHttpUrl(url), "url", "validation.url.invalid");
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
    String visibility = text(node, "visibility", "private");
    if (!VISIBILITIES.contains(visibility)) throw ApiProblem.badRequest("unknown visibility: " + visibility);
    validator.throwIfInvalid();
    return new BookmarkInput(url, title, notes, tags, visibility);
  }

  private MessageInput messageInput(String body) {
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

  private ReportInput reportInput(String body) {
    JsonNode node = JsonSupport.objectNode(body);
    Validator validator = new Validator();
    String reason = text(node, "reason", null);
    validator.check(REPORT_REASONS.contains(reason), "reason", "validation.report.reason.invalid");
    String comment = nullableText(node, "comment");
    validator.check(comment == null || comment.length() <= 1000, "comment", "validation.report.comment.too-long");
    validator.throwIfInvalid();
    return new ReportInput(reason, comment);
  }

  private Map<String, Object> findBookmark(Connection connection, UUID id, boolean lock) throws SQLException {
    try (PreparedStatement statement = RuntimeSupport.prepare(connection,
        "select * from bookmarks where id = ?" + (lock ? " for update" : ""), id)) {
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? bookmark(rs) : null;
      }
    }
  }

  private Map<String, Object> bookmark(ResultSet rs) throws SQLException {
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

  private Map<String, Object> message(ResultSet rs) throws SQLException {
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

  private Map<String, Object> report(ResultSet rs) throws SQLException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("id", rs.getObject("id").toString());
    body.put("bookmarkId", rs.getObject("bookmark_id").toString());
    body.put("reporter", rs.getString("reporter"));
    body.put("reason", rs.getString("reason"));
    put(body, "comment", rs.getString("comment"));
    body.put("status", rs.getString("status"));
    body.put("createdAt", instant(rs, "created_at"));
    put(body, "resolvedBy", rs.getString("resolved_by"));
    Timestamp resolvedAt = rs.getTimestamp("resolved_at");
    if (resolvedAt != null) body.put("resolvedAt", resolvedAt.toInstant().toString());
    put(body, "resolutionNote", rs.getString("resolution_note"));
    return body;
  }

  private Map<String, Object> userAccount(ResultSet rs) throws SQLException {
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", rs.getString("username"));
    body.put("firstSeen", instant(rs, "first_seen"));
    body.put("lastSeen", instant(rs, "last_seen"));
    body.put("status", rs.getString("status"));
    put(body, "blockedReason", rs.getString("blocked_reason"));
    body.put("bookmarkCount", rs.getLong("bookmark_count"));
    return body;
  }

  private Map<String, Object> auditEntry(ResultSet rs) throws SQLException {
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

  private Map<String, Object> resolveOne(Connection connection, String actor, Map<String, Object> report,
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

  private void hideBookmark(Connection connection, String actor, UUID bookmarkId, String note) throws SQLException {
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

  private Map<String, Object> ownReport(Connection connection, String reporter, UUID id) throws SQLException {
    Map<String, Object> report = reportById(connection, id, true);
    if (report == null || !reporter.equals(report.get("reporter"))) throw ApiProblem.notFound();
    return report;
  }

  private Map<String, Object> reportById(Connection connection, UUID id, boolean lock) throws SQLException {
    try (PreparedStatement statement = RuntimeSupport.prepare(connection,
        "select * from reports where id = ?" + (lock ? " for update" : ""), id)) {
      try (ResultSet rs = statement.executeQuery()) {
        return rs.next() ? report(rs) : null;
      }
    }
  }

  private Response reportPage(String itemSql, String countSql, List<Object> params, Paging paging) throws SQLException {
    List<Map<String, Object>> items = new ArrayList<>();
    long total;
    try (Connection connection = RuntimeSupport.connection()) {
      try (PreparedStatement statement = prepare(connection, itemSql, append(params, paging.size(), paging.page() * paging.size()))) {
        try (ResultSet rs = statement.executeQuery()) {
          while (rs.next()) items.add(report(rs));
        }
      }
      try (PreparedStatement statement = prepare(connection, countSql, params)) {
        try (ResultSet rs = statement.executeQuery()) {
          rs.next();
          total = rs.getLong("count");
        }
      }
    }
    return JsonSupport.json(page(items, paging, total));
  }

  private Map<String, Object> findUser(String username) throws SQLException {
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

  private void audit(Connection connection, String actor, String action, String targetType, String targetId, Object detail)
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

  private static Set<String> supportedLanguages() {
    try (Connection connection = RuntimeSupport.connection();
         PreparedStatement statement = connection.prepareStatement("select distinct language from messages")) {
      Set<String> result = new HashSet<>();
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) result.add(rs.getString("language"));
      }
      return result;
    } catch (SQLException ex) {
      throw new RuntimeException(ex);
    }
  }

  private static List<LanguagePreference> parseAcceptLanguage(String header) {
    if (header == null || header.isBlank()) return List.of();
    List<LanguagePreference> result = new ArrayList<>();
    int index = 0;
    for (String part : header.split(",")) {
      String[] pieces = part.trim().split(";");
      String code = pieces[0].trim().toLowerCase().split("-")[0];
      double quality = 1.0;
      for (int i = 1; i < pieces.length; i++) {
        String value = pieces[i].trim();
        if (value.startsWith("q=")) {
          try {
            quality = Double.parseDouble(value.substring(2));
          } catch (NumberFormatException ignored) {
            quality = 0;
          }
        }
      }
      if (code.matches("^[a-z]{1,8}$")) result.add(new LanguagePreference(code, quality, index));
      index++;
    }
    result.sort(Comparator.comparingDouble(LanguagePreference::quality).reversed().thenComparingInt(LanguagePreference::index));
    return result;
  }

  private static Map<String, String> messageBundle(String language) throws SQLException {
    Map<String, String> messages = new LinkedHashMap<>();
    try (Connection connection = RuntimeSupport.connection();
         PreparedStatement statement = RuntimeSupport.prepare(connection,
             """
             select key, language, text
             from messages
             where language = any(?::text[])
             order by key, case when language = ? then 0 else 1 end
             """,
             new String[] {language, "en"}, language)) {
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) messages.putIfAbsent(rs.getString("key"), rs.getString("text"));
      }
    }
    return messages;
  }

  private static long count(String sql) throws SQLException {
    try (Connection connection = RuntimeSupport.connection();
         PreparedStatement statement = connection.prepareStatement(sql);
         ResultSet rs = statement.executeQuery()) {
      rs.next();
      return rs.getLong(1);
    }
  }

  private static Map<String, Integer> countPerDay(String table, String column, Instant from) throws SQLException {
    Map<String, Integer> result = new LinkedHashMap<>();
    try (Connection connection = RuntimeSupport.connection();
         PreparedStatement statement = RuntimeSupport.prepare(connection,
             "select (" + column + " at time zone 'UTC')::date::text as day, count(*)::int as count from " + table
                 + " where " + column + " >= ? group by day",
             from)) {
      try (ResultSet rs = statement.executeQuery()) {
        while (rs.next()) result.put(rs.getString("day"), rs.getInt("count"));
      }
    }
    return result;
  }

  private static boolean exists(Connection connection, String sql, Object... params) throws SQLException {
    try (PreparedStatement statement = RuntimeSupport.prepare(connection, sql, params);
         ResultSet rs = statement.executeQuery()) {
      return rs.next();
    }
  }

  private PreparedStatement prepare(Connection connection, String sql, List<Object> params) throws SQLException {
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

  private Paging paging() {
    int page = integer(single("page"), 0, "page");
    int size = integer(single("size"), 20, "size");
    if (page < 0) throw ApiProblem.badRequest("page must not be negative");
    if (size < 1 || size > 100) throw ApiProblem.badRequest("size must be between 1 and 100");
    return new Paging(page, size);
  }

  private int cursorPageSize() {
    int size = integer(single("size"), 20, "size");
    if (size < 1 || size > 100) throw ApiProblem.badRequest("size must be between 1 and 100");
    return size;
  }

  private String single(String name) {
    List<String> values = uriInfo.getQueryParameters().get(name);
    if (values == null || values.isEmpty()) return null;
    if (values.size() > 1) throw ApiProblem.badRequest(name + " must not be repeated");
    return values.get(0);
  }

  private List<String> validateTags(List<String> raw, String field) {
    List<String> tags = raw.stream().map(tag -> tag.trim().toLowerCase()).toList();
    Validator validator = new Validator();
    validator.check(tags.stream().allMatch(tag -> TAG_PATTERN.matcher(tag).matches()), field, "validation.tag.invalid");
    validator.throwIfInvalid();
    return tags;
  }

  private static String reportStatus(String value, boolean defaultOpen) {
    if (value == null) return defaultOpen ? "open" : null;
    if (!REPORT_STATUSES.contains(value)) throw ApiProblem.badRequest("unknown status: " + value);
    return value;
  }

  private static UUID uuid(String raw) {
    try {
      return UUID.fromString(raw);
    } catch (IllegalArgumentException ex) {
      throw ApiProblem.notFound();
    }
  }

  private static boolean visibleTo(Map<String, Object> bookmark, Caller caller) {
    return caller != null && caller.username().equals(bookmark.get("owner"))
        || "public".equals(bookmark.get("visibility")) && "active".equals(bookmark.get("status"));
  }

  private static boolean isHttpUrl(String value) {
    try {
      URI uri = URI.create(value);
      return ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme())) && uri.getHost() != null;
    } catch (IllegalArgumentException ex) {
      return false;
    }
  }

  private static String text(JsonNode node, String field, String fallback) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() ? fallback : value.asText();
  }

  private static String nullableText(JsonNode node, String field) {
    JsonNode value = node.get(field);
    return value == null || value.isNull() || !value.isTextual() ? null : value.asText();
  }

  private static int integer(String raw, int fallback, String name) {
    if (raw == null || raw.isEmpty()) return fallback;
    try {
      return Integer.parseInt(raw);
    } catch (NumberFormatException ex) {
      throw ApiProblem.badRequest(name + " must be an integer");
    }
  }

  private static void requireMax(String value, int max, String name) {
    if (value != null && value.length() > max) throw ApiProblem.badRequest(name + " must be at most " + max + " characters");
  }

  private static String escapeLike(String value) {
    return value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
  }

  private static List<Object> append(List<Object> params, Object... values) {
    List<Object> result = new ArrayList<>(params);
    result.addAll(List.of(values));
    return result;
  }

  private static Map<String, Object> page(List<Map<String, Object>> items, Paging paging, long totalItems) {
    return linked("items", items, "page", paging.page(), "size", paging.size(), "totalItems", totalItems,
        "totalPages", (int) Math.ceil(totalItems / (double) paging.size()));
  }

  private static Map<String, Object> linked(Object... pairs) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int i = 0; i < pairs.length; i += 2) {
      put(result, (String) pairs[i], pairs[i + 1]);
    }
    return result;
  }

  private static void put(Map<String, Object> map, String key, Object value) {
    if (value != null) map.put(key, value);
  }

  private static String instant(ResultSet rs, String column) throws SQLException {
    return rs.getTimestamp(column).toInstant().toString();
  }

  private static List<String> array(ResultSet rs, String column) throws SQLException {
    Object value = rs.getArray(column).getArray();
    if (value instanceof String[] strings) return List.of(strings);
    Object[] objects = (Object[]) value;
    List<String> result = new ArrayList<>();
    for (Object object : objects) result.add(String.valueOf(object));
    return result;
  }

  private static Instant parseInstant(String value, String name) {
    try {
      return Instant.parse(value);
    } catch (Exception ex) {
      throw ApiProblem.badRequest(name + " must be an RFC 3339 date-time");
    }
  }

  private static Cursor decodeCursor(String raw) {
    try {
      String decoded = new String(Base64.getUrlDecoder().decode(raw), java.nio.charset.StandardCharsets.UTF_8);
      int separator = decoded.indexOf('|');
      if (separator < 0) throw new IllegalArgumentException();
      return new Cursor(Instant.parse(decoded.substring(0, separator)), UUID.fromString(decoded.substring(separator + 1)));
    } catch (Exception ex) {
      throw ApiProblem.badRequest("The cursor is malformed or unresolvable.");
    }
  }

  private static String encodeCursor(Instant createdAt, UUID id) {
    String raw = createdAt + "|" + id;
    return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
  }
}

record Paging(int page, int size) {}
record ListFilters(List<String> tags, String q, String visibility) {}
record QueryParts(String where, List<Object> params) {}
record Cursor(Instant createdAt, UUID id) {}
record LanguagePreference(String code, double quality, int index) {}
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
