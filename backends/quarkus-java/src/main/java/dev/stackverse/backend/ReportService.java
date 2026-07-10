package dev.stackverse.backend;

import static dev.stackverse.backend.HttpResponses.pageResponse;
import static dev.stackverse.backend.PersistenceSupport.detail;
import static dev.stackverse.backend.PersistenceSupport.execute;
import static dev.stackverse.backend.PersistenceSupport.instant;
import static dev.stackverse.backend.PersistenceSupport.isUniqueViolation;
import static dev.stackverse.backend.PersistenceSupport.now;
import static dev.stackverse.backend.PersistenceSupport.nullableInstant;
import static dev.stackverse.backend.PersistenceSupport.params;
import static dev.stackverse.backend.PersistenceSupport.query;
import static dev.stackverse.backend.PersistenceSupport.queryOne;
import static dev.stackverse.backend.PersistenceSupport.scalarLong;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ReportService {
    private static final Logger LOG = Logger.getLogger(ReportService.class);

    private final DatabaseOperations database;
    private final Authorization authorization;
    private final RequestParameters requestParameters;
    private final AuditTrail auditTrail;

    public ReportService(
            DatabaseOperations database,
            Authorization authorization,
            RequestParameters requestParameters,
            AuditTrail auditTrail) {
        this.database = database;
        this.authorization = authorization;
        this.requestParameters = requestParameters;
        this.auditTrail = auditTrail;
    }

    public Response reportBookmark(String rawId, ReportInput input) {
        Caller caller = authorization.requireCaller();
        UUID bookmarkId = requestParameters.parseUuid(rawId);
        Report report =
                database.inTransaction(
                        connection -> {
                            Optional<Bookmark> bookmark =
                                    queryOne(
                                            connection,
                                            "select * from bookmarks where id = ? for update",
                                            List.of(bookmarkId),
                                            BookmarkService::bookmark);
                            if (bookmark.isEmpty()
                                    || !"public".equals(bookmark.get().visibility())
                                    || !"active".equals(bookmark.get().status())) {
                                throw StackverseProblem.notFound();
                            }
                            boolean duplicate =
                                    scalarLong(
                                                    connection,
                                                    "select count(*) from reports where bookmark_id = ? and reporter = ? and status = 'open'",
                                                    List.of(bookmarkId, caller.username()))
                                            > 0;
                            if (duplicate) {
                                throw StackverseProblem.conflict(
                                        "You already have an open report on this bookmark.");
                            }
                            try {
                                return queryOne(
                                                connection,
                                                "insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)"
                                                        + " values (?, ?, ?, ?, ?, 'open', ?) returning *",
                                                params(
                                                        UUID.randomUUID(),
                                                        bookmarkId,
                                                        caller.username(),
                                                        input.reason(),
                                                        input.comment(),
                                                        now()),
                                                ReportService::report)
                                        .orElseThrow();
                            } catch (RuntimeException error) {
                                if (isUniqueViolation(error)) {
                                    throw StackverseProblem.conflict(
                                            "You already have an open report on this bookmark.");
                                }
                                throw error;
                            }
                        });
        StackverseLog.event(
                LOG,
                Logger.Level.INFO,
                "report_created",
                "success",
                "Report created on a public bookmark",
                Map.of(
                        "actor",
                        caller.username(),
                        "resource_type",
                        "report",
                        "resource_id",
                        report.id().toString(),
                        "bookmark_id",
                        bookmarkId.toString(),
                        "reason",
                        report.reason()));
        return Response.status(Response.Status.CREATED).entity(reportResponse(report)).build();
    }

    public Response listMyReports(RequestContext request) {
        Caller caller = authorization.requireCaller();
        int page = requestParameters.pagingPage(request);
        int size = requestParameters.pageSize(request);
        String status = requestParameters.singleParam(request, "status");
        if (status != null && !validReportStatus(status)) {
            throw StackverseProblem.badRequest("status must be one of: open, dismissed, actioned");
        }
        PageResponse<ReportResponse> body =
                database.withConnection(
                        connection -> {
                            SqlWhere where = new SqlWhere();
                            where.and("reporter = ?", caller.username());
                            if (status != null) {
                                where.and("status = ?", status);
                            }
                            long total =
                                    scalarLong(
                                            connection,
                                            "select count(*) from reports " + where.sql(),
                                            where.params());
                            List<Object> params = new ArrayList<>(where.params());
                            params.add(size);
                            params.add(requestParameters.offset(page, size));
                            List<ReportResponse> items =
                                    query(
                                            connection,
                                            "select * from reports "
                                                    + where.sql()
                                                    + " order by created_at desc, id desc limit ? offset ?",
                                            params,
                                            rs -> reportResponse(report(rs)));
                            return pageResponse(items, page, size, total);
                        });
        return Response.ok(body).build();
    }

    public Response updateMyReport(String rawId, ReportInput input) {
        Caller caller = authorization.requireCaller();
        UUID id = requestParameters.parseUuid(rawId);
        Report updated =
                database.inTransaction(
                        connection -> {
                            Report report = ownReportForUpdate(connection, caller.username(), id);
                            if (!"open".equals(report.status())) {
                                throw StackverseProblem.conflict(
                                        "The report has already been resolved.");
                            }
                            return queryOne(
                                            connection,
                                            "update reports set reason = ?, comment = ? where id = ? returning *",
                                            params(input.reason(), input.comment(), id),
                                            ReportService::report)
                                    .orElseThrow();
                        });
        StackverseLog.event(
                LOG,
                Logger.Level.INFO,
                "report_updated",
                "success",
                "Report updated by its reporter",
                Map.of(
                        "actor",
                        caller.username(),
                        "resource_type",
                        "report",
                        "resource_id",
                        updated.id().toString(),
                        "bookmark_id",
                        updated.bookmarkId().toString(),
                        "reason",
                        updated.reason()));
        return Response.ok(reportResponse(updated)).build();
    }

    public Response withdrawReport(String rawId) {
        Caller caller = authorization.requireCaller();
        UUID id = requestParameters.parseUuid(rawId);
        Report withdrawn =
                database.inTransaction(
                        connection -> {
                            Report report = ownReportForUpdate(connection, caller.username(), id);
                            if (!"open".equals(report.status())) {
                                throw StackverseProblem.conflict(
                                        "The report has already been resolved.");
                            }
                            execute(connection, "delete from reports where id = ?", List.of(id));
                            return report;
                        });
        StackverseLog.event(
                LOG,
                Logger.Level.INFO,
                "report_withdrawn",
                "success",
                "Report withdrawn by its reporter",
                Map.of(
                        "actor",
                        caller.username(),
                        "resource_type",
                        "report",
                        "resource_id",
                        withdrawn.id().toString(),
                        "bookmark_id",
                        withdrawn.bookmarkId().toString()));
        return Response.noContent().build();
    }

    public Response listReportQueue(RequestContext request) {
        authorization.requireRole("moderator");
        int page = requestParameters.pagingPage(request);
        int size = requestParameters.pageSize(request);
        String status =
                Optional.ofNullable(requestParameters.singleParam(request, "status"))
                        .orElse("open");
        if (!validReportStatus(status)) {
            throw StackverseProblem.badRequest("status must be one of: open, dismissed, actioned");
        }
        PageResponse<ReportResponse> body =
                database.withConnection(
                        connection -> {
                            long total =
                                    scalarLong(
                                            connection,
                                            "select count(*) from reports where status = ?",
                                            List.of(status));
                            List<ReportResponse> items =
                                    query(
                                            connection,
                                            "select * from reports where status = ? order by created_at asc, id asc limit ? offset ?",
                                            List.of(
                                                    status,
                                                    size,
                                                    requestParameters.offset(page, size)),
                                            rs -> reportResponse(report(rs)));
                            return pageResponse(items, page, size, total);
                        });
        return Response.ok(body).build();
    }

    public Response resolveReport(String rawId, ResolutionInput input) {
        Caller caller = authorization.requireRole("moderator");
        UUID id = requestParameters.parseUuid(rawId);
        List<Runnable> events = new ArrayList<>();
        Report resolved =
                database.inTransaction(
                        connection -> {
                            if ("actioned".equals(input.resolution())) {
                                UUID bookmarkId =
                                        queryOne(
                                                        connection,
                                                        "select bookmark_id from reports where id = ?",
                                                        List.of(id),
                                                        rs -> (UUID) rs.getObject("bookmark_id"))
                                                .orElseThrow(StackverseProblem::notFound);
                                queryOne(
                                                connection,
                                                "select id from bookmarks where id = ? for update",
                                                List.of(bookmarkId),
                                                rs -> rs.getObject("id"))
                                        .orElseThrow(StackverseProblem::notFound);
                            }
                            Report locked =
                                    queryOne(
                                                    connection,
                                                    "select * from reports where id = ? for update",
                                                    List.of(id),
                                                    ReportService::report)
                                            .orElseThrow(StackverseProblem::notFound);
                            if ("open".equals(input.resolution())) {
                                return reopenReport(connection, caller, locked, events);
                            }
                            Report primary =
                                    resolveOne(
                                            connection,
                                            caller,
                                            locked,
                                            input.resolution(),
                                            input.note(),
                                            false,
                                            events);
                            if ("actioned".equals(input.resolution())) {
                                hideBookmark(
                                        connection,
                                        caller,
                                        locked.bookmarkId(),
                                        input.note(),
                                        events);
                                List<Report> siblings =
                                        query(
                                                connection,
                                                "select * from reports where bookmark_id = ? and status = 'open' and id <> ? order by id for update",
                                                List.of(locked.bookmarkId(), locked.id()),
                                                ReportService::report);
                                for (Report sibling : siblings) {
                                    resolveOne(
                                            connection,
                                            caller,
                                            sibling,
                                            "actioned",
                                            input.note(),
                                            true,
                                            events);
                                }
                            }
                            return primary;
                        });
        events.forEach(Runnable::run);
        return Response.ok(reportResponse(resolved)).build();
    }

    public Response setBookmarkStatus(String rawId, BookmarkStatusInput input) {
        Caller caller = authorization.requireRole("moderator");
        UUID id = requestParameters.parseUuid(rawId);
        StatusChange change =
                database.inTransaction(
                        connection -> {
                            Bookmark bookmark =
                                    queryOne(
                                                    connection,
                                                    "select * from bookmarks where id = ? for update",
                                                    List.of(id),
                                                    BookmarkService::bookmark)
                                            .orElseThrow(StackverseProblem::notFound);
                            Bookmark updated =
                                    queryOne(
                                                    connection,
                                                    "update bookmarks set status = ?, updated_at = ? where id = ? returning *",
                                                    List.of(input.status(), now(), id),
                                                    BookmarkService::bookmark)
                                            .orElseThrow();
                            auditTrail.record(
                                    connection,
                                    caller.username(),
                                    "bookmark.status-changed",
                                    "bookmark",
                                    id.toString(),
                                    detail(
                                            "from",
                                            bookmark.status(),
                                            "to",
                                            input.status(),
                                            "note",
                                            input.note()));
                            return new StatusChange(bookmark.status(), updated);
                        });
        StackverseLog.event(
                LOG,
                Logger.Level.INFO,
                "bookmark_status_changed",
                "success",
                "Bookmark moderation status changed",
                Map.of(
                        "actor",
                        caller.username(),
                        "resource_type",
                        "bookmark",
                        "resource_id",
                        id.toString(),
                        "from",
                        change.previous(),
                        "to",
                        change.bookmark().status()));
        return Response.ok(BookmarkService.bookmarkResponse(change.bookmark())).build();
    }

    private Report reopenReport(
            Connection connection, Caller caller, Report report, List<Runnable> events) {
        boolean duplicate =
                scalarLong(
                                connection,
                                "select count(*) from reports where bookmark_id = ? and reporter = ? and status = 'open' and id <> ?",
                                List.of(report.bookmarkId(), report.reporter(), report.id()))
                        > 0;
        if (duplicate) {
            throw StackverseProblem.conflict(
                    "The reporter already has another open report on this bookmark.");
        }
        Report reopened;
        try {
            reopened =
                    queryOne(
                                    connection,
                                    "update reports set status = 'open', resolved_by = null, resolved_at = null, resolution_note = null"
                                            + " where id = ? returning *",
                                    List.of(report.id()),
                                    ReportService::report)
                            .orElseThrow();
        } catch (RuntimeException error) {
            if (isUniqueViolation(error)) {
                throw StackverseProblem.conflict(
                        "The reporter already has another open report on this bookmark.");
            }
            throw error;
        }
        auditTrail.record(
                connection,
                caller.username(),
                "report.reopened",
                "report",
                report.id().toString(),
                Map.of("bookmarkId", report.bookmarkId().toString()));
        events.add(
                () ->
                        StackverseLog.event(
                                LOG,
                                Logger.Level.INFO,
                                "report_reopened",
                                "success",
                                "Report re-opened",
                                Map.of(
                                        "actor",
                                        caller.username(),
                                        "resource_type",
                                        "report",
                                        "resource_id",
                                        report.id().toString(),
                                        "bookmark_id",
                                        report.bookmarkId().toString())));
        return reopened;
    }

    private Report resolveOne(
            Connection connection,
            Caller caller,
            Report report,
            String resolution,
            String note,
            boolean autoResolved,
            List<Runnable> events) {
        Instant resolvedAt = now();
        Report updated =
                queryOne(
                                connection,
                                "update reports set status = ?, resolved_by = ?, resolved_at = ?, resolution_note = ?"
                                        + " where id = ? returning *",
                                params(
                                        resolution,
                                        caller.username(),
                                        resolvedAt,
                                        note,
                                        report.id()),
                                ReportService::report)
                        .orElseThrow();
        auditTrail.record(
                connection,
                caller.username(),
                "report.resolved",
                "report",
                report.id().toString(),
                detail(
                        "bookmarkId",
                        report.bookmarkId().toString(),
                        "resolution",
                        resolution,
                        "note",
                        note,
                        "autoResolved",
                        autoResolved));
        events.add(
                () ->
                        StackverseLog.event(
                                LOG,
                                Logger.Level.INFO,
                                "report_resolved",
                                "success",
                                "Report resolved",
                                Map.of(
                                        "actor",
                                        caller.username(),
                                        "resource_type",
                                        "report",
                                        "resource_id",
                                        report.id().toString(),
                                        "bookmark_id",
                                        report.bookmarkId().toString(),
                                        "resolution",
                                        resolution,
                                        "auto_resolved",
                                        autoResolved)));
        return updated;
    }

    private void hideBookmark(
            Connection connection,
            Caller caller,
            UUID bookmarkId,
            String note,
            List<Runnable> events) {
        Bookmark bookmark =
                queryOne(
                                connection,
                                "select * from bookmarks where id = ?",
                                List.of(bookmarkId),
                                BookmarkService::bookmark)
                        .orElseThrow(StackverseProblem::notFound);
        if ("hidden".equals(bookmark.status())) {
            return;
        }
        execute(
                connection,
                "update bookmarks set status = 'hidden', updated_at = ? where id = ?",
                List.of(now(), bookmarkId));
        auditTrail.record(
                connection,
                caller.username(),
                "bookmark.status-changed",
                "bookmark",
                bookmarkId.toString(),
                detail("from", bookmark.status(), "to", "hidden", "note", note));
        events.add(
                () ->
                        StackverseLog.event(
                                LOG,
                                Logger.Level.INFO,
                                "bookmark_status_changed",
                                "success",
                                "Bookmark hidden by an actioned report",
                                Map.of(
                                        "actor",
                                        caller.username(),
                                        "resource_type",
                                        "bookmark",
                                        "resource_id",
                                        bookmarkId.toString(),
                                        "from",
                                        bookmark.status(),
                                        "to",
                                        "hidden")));
    }

    private Report ownReportForUpdate(Connection connection, String reporter, UUID id) {
        Report report =
                queryOne(
                                connection,
                                "select * from reports where id = ? for update",
                                List.of(id),
                                ReportService::report)
                        .orElseThrow(StackverseProblem::notFound);
        if (!report.reporter().equals(reporter)) {
            throw StackverseProblem.notFound();
        }
        return report;
    }

    private static Report report(ResultSet rs) throws SQLException {
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

    private static boolean validReportStatus(String status) {
        return Set.of("open", "dismissed", "actioned").contains(status);
    }
}
