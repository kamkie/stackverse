package dev.stackverse.openliberty;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ModerationResource extends ResourceSupport {
  @GET
  @Path("/api/v1/admin/reports")
  public Response reports() throws SQLException {
    requireRole("moderator");
    Paging paging = paging();
    String status = reportStatus(single("status"), true);
    ResponsePage result = reportPage(
        "status = ?",
        new ArrayList<>(List.of(status)),
        paging,
        "order by created_at asc, id asc");
    return JsonSupport.json(page(result.items(), paging, result.total()));
  }

  @PUT
  @Path("/api/v1/admin/reports/{id}")
  public Response resolveReport(@PathParam("id") String rawId, String body) {
    Caller caller = requireRole("moderator");
    UUID id = uuid(rawId);
    JsonNode node = JsonSupport.objectNode(body);
    Validator validator = new Validator();
    String resolution = text(node, "resolution", null);
    validator.check("open".equals(resolution) || "dismissed".equals(resolution) || "actioned".equals(resolution),
        "resolution", "validation.resolution.invalid");
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
        try (PreparedStatement lock = RuntimeSupport.prepare(connection, "select id from bookmarks where id = ? for update", bookmarkId);
             ResultSet ignored = lock.executeQuery()) {
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
}
