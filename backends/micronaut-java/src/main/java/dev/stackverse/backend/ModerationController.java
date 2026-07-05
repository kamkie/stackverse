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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
final class ModerationController {
    private static final Logger LOG = LoggerFactory.getLogger(ModerationController.class);

    private final Database db;
    private final SecuritySupport security;
    private final AuditService audit;
    private final BookmarksController bookmarks;

    ModerationController(Database db, SecuritySupport security, AuditService audit, BookmarksController bookmarks) {
        this.db = db;
        this.security = security;
        this.audit = audit;
        this.bookmarks = bookmarks;
    }

    @Post("/api/v1/bookmarks/{id}/reports")
    MutableHttpResponse<ReportResponse> report(HttpRequest<?> request, @PathVariable String id, @Body ReportInput body) {
        Identity reporter = security.require(request);
        UUID bookmarkId = WebSupport.uuid(id, "id");
        Bookmark bookmark = bookmarks.byId(bookmarkId);
        if (!Models.PUBLIC.equals(bookmark.visibility()) || !Models.ACTIVE.equals(bookmark.status())) {
            throw Problems.notFound();
        }
        validateReport(body);
        if (db.scalarBoolean("select exists (select 1 from reports where bookmark_id = ? and reporter = ? and status = 'open')",
                bookmarkId, reporter.username())) {
            throw Problems.conflict("You already have an open report on this bookmark.");
        }
        Report created = new Report(UUID.randomUUID(), bookmarkId, reporter.username(), body.reason(), body.comment(),
                Models.OPEN, null, null, null, WebSupport.now());
        db.update("""
                insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """, created.id(), created.bookmarkId(), created.reporter(), created.reason(), created.comment(),
                created.status(), created.createdAt());
        EventLog.info(LOG, "report_created", "success", "Report created on a public bookmark",
                Map.of("actor", reporter.username(), "resource_type", "report", "resource_id", created.id().toString(),
                        "bookmark_id", bookmarkId.toString(), "reason", created.reason()));
        return HttpResponse.created(ReportResponse.from(created));
    }

    @Get("/api/v1/reports")
    PageResponse<ReportResponse> listMine(HttpRequest<?> request) {
        Identity reporter = security.require(request);
        int page = WebSupport.page(request);
        int size = WebSupport.size(request);
        String status = request.getParameters().getFirst("status").orElse("");
        if (!status.isBlank() && !validReportStatus(status)) {
            throw Problems.badRequest("status must be one of: open, dismissed, actioned");
        }
        long total = db.scalarLong("select count(*) from reports where reporter = ? and (? = '' or status = ?)",
                reporter.username(), status, status);
        List<ReportResponse> items = db.query("""
                select id, bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at
                from reports
                where reporter = ? and (? = '' or status = ?)
                order by created_at desc, id desc
                limit ? offset ?
                """, Models::report, reporter.username(), status, status, size, WebSupport.offset(page, size))
                .stream().map(ReportResponse::from).toList();
        return WebSupport.pageResponse(items, page, size, total);
    }

    @Put("/api/v1/reports/{id}")
    ReportResponse updateMine(HttpRequest<?> request, @PathVariable String id, @Body ReportInput body) {
        Identity reporter = security.require(request);
        UUID reportId = WebSupport.uuid(id, "id");
        validateReport(body);
        Report updated = db.inTx(connection -> {
            Report locked = lockReport(connection, reportId);
            if (!locked.reporter().equals(reporter.username())) {
                throw Problems.notFound();
            }
            if (!Models.OPEN.equals(locked.status())) {
                throw Problems.conflict("The report has already been resolved.");
            }
            db.update(connection, "update reports set reason = ?, comment = ? where id = ?",
                    body.reason(), body.comment(), locked.id());
            return new Report(locked.id(), locked.bookmarkId(), locked.reporter(), body.reason(), body.comment(),
                    locked.status(), locked.resolvedBy(), locked.resolvedAt(), locked.resolutionNote(), locked.createdAt());
        });
        EventLog.info(LOG, "report_updated", "success", "Report updated by its reporter",
                Map.of("actor", reporter.username(), "resource_type", "report", "resource_id", updated.id().toString(),
                        "bookmark_id", updated.bookmarkId().toString(), "reason", updated.reason()));
        return ReportResponse.from(updated);
    }

    @Delete("/api/v1/reports/{id}")
    HttpResponse<?> withdraw(HttpRequest<?> request, @PathVariable String id) {
        Identity reporter = security.require(request);
        UUID reportId = WebSupport.uuid(id, "id");
        Report withdrawn = db.inTx(connection -> {
            Report locked = lockReport(connection, reportId);
            if (!locked.reporter().equals(reporter.username())) {
                throw Problems.notFound();
            }
            if (!Models.OPEN.equals(locked.status())) {
                throw Problems.conflict("The report has already been resolved.");
            }
            db.update(connection, "delete from reports where id = ?", locked.id());
            return locked;
        });
        EventLog.info(LOG, "report_withdrawn", "success", "Report withdrawn by its reporter",
                Map.of("actor", reporter.username(), "resource_type", "report", "resource_id", withdrawn.id().toString(),
                        "bookmark_id", withdrawn.bookmarkId().toString()));
        return HttpResponse.noContent();
    }

    @Get("/api/v1/admin/reports")
    PageResponse<ReportResponse> listQueue(HttpRequest<?> request) {
        security.requireRole(request, "moderator");
        int page = WebSupport.page(request);
        int size = WebSupport.size(request);
        String status = request.getParameters().getFirst("status").orElse(Models.OPEN);
        if (!validReportStatus(status)) {
            throw Problems.badRequest("status must be one of: open, dismissed, actioned");
        }
        long total = db.scalarLong("select count(*) from reports where status = ?", status);
        List<ReportResponse> items = db.query("""
                select id, bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at
                from reports
                where status = ?
                order by created_at, id
                limit ? offset ?
                """, Models::report, status, size, WebSupport.offset(page, size)).stream().map(ReportResponse::from).toList();
        return WebSupport.pageResponse(items, page, size, total);
    }

    @Put("/api/v1/admin/reports/{id}")
    ReportResponse resolve(HttpRequest<?> request, @PathVariable String id, @Body ReportResolutionInput body) {
        Identity actor = security.requireRole(request, "moderator");
        UUID reportId = WebSupport.uuid(id, "id");
        validateResolution(body);
        List<Runnable> events = new ArrayList<>();
        Report resolved = db.inTx(connection -> {
            if (Models.ACTIONED.equals(body.resolution())) {
                UUID bookmarkId = db.one(connection, "select bookmark_id from reports where id = ?",
                        rs -> rs.getObject("bookmark_id", UUID.class), reportId);
                bookmarks.lockById(connection, bookmarkId);
            }
            Report locked = lockReport(connection, reportId);
            if (Models.OPEN.equals(body.resolution())) {
                return reopen(connection, locked, actor.username(), events);
            }
            Report main = resolveOne(connection, locked, body.resolution(), actor.username(), body.note(), false, events);
            if (Models.ACTIONED.equals(body.resolution())) {
                hideBookmark(connection, locked.bookmarkId(), actor.username(), body.note(), events);
                List<Report> siblings = db.query(connection, """
                        select id, bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at
                        from reports
                        where bookmark_id = ? and status = 'open' and id <> ?
                        order by id for update
                        """, Models::report, locked.bookmarkId(), locked.id());
                for (Report sibling : siblings) {
                    resolveOne(connection, sibling, Models.ACTIONED, actor.username(), body.note(), true, events);
                }
            }
            return main;
        });
        events.forEach(Runnable::run);
        return ReportResponse.from(resolved);
    }

    @Put("/api/v1/admin/bookmarks/{id}/status")
    BookmarkResponse setBookmarkStatus(HttpRequest<?> request, @PathVariable String id, @Body BookmarkStatusInput body) {
        Identity actor = security.requireRole(request, "moderator");
        UUID bookmarkId = WebSupport.uuid(id, "id");
        validateBookmarkStatus(body);
        StatusChange change = db.inTx(connection -> {
            Bookmark locked = bookmarks.lockById(connection, bookmarkId);
            String previous = locked.status();
            Bookmark updated = new Bookmark(locked.id(), locked.owner(), locked.url(), locked.title(), locked.notes(),
                    locked.tags(), locked.visibility(), body.status(), locked.createdAt(), WebSupport.now());
            db.update(connection, "update bookmarks set status = ?, updated_at = ? where id = ?",
                    updated.status(), updated.updatedAt(), updated.id());
            audit.record(connection, actor.username(), "bookmark.status-changed", "bookmark", updated.id().toString(),
                    nullableMap("from", previous, "to", updated.status(), "note", body.note()));
            return new StatusChange(previous, updated);
        });
        EventLog.info(LOG, "bookmark_status_changed", "success", "Bookmark moderation status changed",
                Map.of("actor", actor.username(), "resource_type", "bookmark",
                        "resource_id", change.bookmark().id().toString(), "from", change.previous(), "to", change.bookmark().status()));
        return BookmarkResponse.from(change.bookmark());
    }

    private Report lockReport(Connection connection, UUID id) throws SQLException {
        return db.one(connection, """
                select id, bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at
                from reports where id = ? for update
                """, Models::report, id);
    }

    private Report reopen(Connection connection, Report locked, String actor, List<Runnable> events) throws SQLException {
        if (db.scalarBoolean(connection,
                "select exists (select 1 from reports where bookmark_id = ? and reporter = ? and status = 'open' and id <> ?)",
                locked.bookmarkId(), locked.reporter(), locked.id())) {
            throw Problems.conflict("The reporter already has another open report on this bookmark.");
        }
        db.update(connection,
                "update reports set status = 'open', resolved_by = null, resolved_at = null, resolution_note = null where id = ?",
                locked.id());
        audit.record(connection, actor, "report.reopened", "report", locked.id().toString(),
                Map.of("bookmarkId", locked.bookmarkId().toString()));
        events.add(() -> EventLog.info(LOG, "report_reopened", "success", "Report re-opened",
                Map.of("actor", actor, "resource_type", "report", "resource_id", locked.id().toString(),
                        "bookmark_id", locked.bookmarkId().toString())));
        return new Report(locked.id(), locked.bookmarkId(), locked.reporter(), locked.reason(), locked.comment(),
                Models.OPEN, null, null, null, locked.createdAt());
    }

    private Report resolveOne(Connection connection, Report locked, String resolution, String actor, String note,
                              boolean autoResolved, List<Runnable> events) throws SQLException {
        Instant now = WebSupport.now();
        db.update(connection,
                "update reports set status = ?, resolved_by = ?, resolved_at = ?, resolution_note = ? where id = ?",
                resolution, actor, now, note, locked.id());
        audit.record(connection, actor, "report.resolved", "report", locked.id().toString(),
                nullableMap("bookmarkId", locked.bookmarkId().toString(), "resolution", resolution, "note", note,
                        "autoResolved", autoResolved));
        events.add(() -> EventLog.info(LOG, "report_resolved", "success", "Report resolved",
                Map.of("actor", actor, "resource_type", "report", "resource_id", locked.id().toString(),
                        "bookmark_id", locked.bookmarkId().toString(), "resolution", resolution,
                        "auto_resolved", autoResolved)));
        return new Report(locked.id(), locked.bookmarkId(), locked.reporter(), locked.reason(), locked.comment(),
                resolution, actor, now, note, locked.createdAt());
    }

    private void hideBookmark(Connection connection, UUID bookmarkId, String actor, String note, List<Runnable> events) throws SQLException {
        Bookmark locked = bookmarks.lockById(connection, bookmarkId);
        if (Models.HIDDEN.equals(locked.status())) {
            return;
        }
        db.update(connection, "update bookmarks set status = 'hidden', updated_at = ? where id = ?", WebSupport.now(), bookmarkId);
        audit.record(connection, actor, "bookmark.status-changed", "bookmark", bookmarkId.toString(),
                nullableMap("from", Models.ACTIVE, "to", Models.HIDDEN, "note", note));
        events.add(() -> EventLog.info(LOG, "bookmark_status_changed", "success", "Bookmark hidden by an actioned report",
                Map.of("actor", actor, "resource_type", "bookmark", "resource_id", bookmarkId.toString(),
                        "from", Models.ACTIVE, "to", Models.HIDDEN)));
    }

    private void validateReport(ReportInput body) {
        Validator validator = new Validator();
        validator.check(body != null && validReason(body.reason()), "reason", "validation.report.reason.invalid");
        validator.check(WebSupport.length(body == null ? null : body.comment()) <= 1000,
                "comment", "validation.report.comment.too-long");
        validator.throwIfInvalid();
    }

    private void validateResolution(ReportResolutionInput body) {
        Validator validator = new Validator();
        validator.check(body != null && validReportStatus(body.resolution()), "resolution", "validation.resolution.invalid");
        validator.check(WebSupport.length(body == null ? null : body.note()) <= 1000,
                "note", "validation.resolution.note.too-long");
        validator.throwIfInvalid();
    }

    private void validateBookmarkStatus(BookmarkStatusInput body) {
        Validator validator = new Validator();
        validator.check(body != null && (Models.ACTIVE.equals(body.status()) || Models.HIDDEN.equals(body.status())),
                "status", "validation.bookmark-status.invalid");
        validator.check(WebSupport.length(body == null ? null : body.note()) <= 1000,
                "note", "validation.bookmark-status.note.too-long");
        validator.throwIfInvalid();
    }

    private boolean validReason(String reason) {
        return List.of("spam", "offensive", "broken-link", "other").contains(reason);
    }

    private boolean validReportStatus(String status) {
        return List.of(Models.OPEN, Models.DISMISSED, Models.ACTIONED).contains(status);
    }

    private Map<String, Object> nullableMap(Object... values) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < values.length; i += 2) {
            map.put((String) values[i], values[i + 1]);
        }
        return map;
    }

    private record StatusChange(String previous, Bookmark bookmark) {
    }
}
