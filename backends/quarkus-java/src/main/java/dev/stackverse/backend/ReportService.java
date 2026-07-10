package dev.stackverse.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.sql.DataSource;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ReportService extends ServiceSupport {
    @Inject
    public ReportService(
            DataSource dataSource,
            JsonWebToken jwt,
            SecurityIdentity securityIdentity,
            ObjectMapper mapper,
            Localizer localizer) {
        super(dataSource, jwt, securityIdentity, mapper, localizer);
    }

    public Response reportBookmark(String rawId, ReportInput input) {
        Caller caller = requireCaller();
        UUID bookmarkId = parseUuid(rawId);
        Report report =
                inTransaction(
                        connection -> {
                            Optional<Bookmark> bookmark =
                                    queryOne(
                                            connection,
                                            "select * from bookmarks where id = ? for update",
                                            List.of(bookmarkId),
                                            ServiceSupport::bookmark);
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
                                                ServiceSupport::report)
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
        Caller caller = requireCaller();
        int page = pagingPage(request);
        int size = pageSize(request);
        String status = singleParam(request, "status");
        if (status != null && !validReportStatus(status)) {
            throw StackverseProblem.badRequest("status must be one of: open, dismissed, actioned");
        }
        PageResponse<ReportResponse> body =
                withConnection(
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
                            params.add(offset(page, size));
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
        Caller caller = requireCaller();
        UUID id = parseUuid(rawId);
        Report updated =
                inTransaction(
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
                                            ServiceSupport::report)
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
        Caller caller = requireCaller();
        UUID id = parseUuid(rawId);
        Report withdrawn =
                inTransaction(
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
        requireRole("moderator");
        int page = pagingPage(request);
        int size = pageSize(request);
        String status = Optional.ofNullable(singleParam(request, "status")).orElse("open");
        if (!validReportStatus(status)) {
            throw StackverseProblem.badRequest("status must be one of: open, dismissed, actioned");
        }
        PageResponse<ReportResponse> body =
                withConnection(
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
                                            List.of(status, size, offset(page, size)),
                                            rs -> reportResponse(report(rs)));
                            return pageResponse(items, page, size, total);
                        });
        return Response.ok(body).build();
    }

    public Response resolveReport(String rawId, ResolutionInput input) {
        Caller caller = requireRole("moderator");
        UUID id = parseUuid(rawId);
        List<Runnable> events = new ArrayList<>();
        Report resolved =
                inTransaction(
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
                                                    ServiceSupport::report)
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
                                                ServiceSupport::report);
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
        Caller caller = requireRole("moderator");
        UUID id = parseUuid(rawId);
        StatusChange change =
                inTransaction(
                        connection -> {
                            Bookmark bookmark =
                                    queryOne(
                                                    connection,
                                                    "select * from bookmarks where id = ? for update",
                                                    List.of(id),
                                                    ServiceSupport::bookmark)
                                            .orElseThrow(StackverseProblem::notFound);
                            Bookmark updated =
                                    queryOne(
                                                    connection,
                                                    "update bookmarks set status = ?, updated_at = ? where id = ? returning *",
                                                    List.of(input.status(), now(), id),
                                                    ServiceSupport::bookmark)
                                            .orElseThrow();
                            recordAudit(
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
        return Response.ok(bookmarkResponse(change.bookmark())).build();
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
                                    ServiceSupport::report)
                            .orElseThrow();
        } catch (RuntimeException error) {
            if (isUniqueViolation(error)) {
                throw StackverseProblem.conflict(
                        "The reporter already has another open report on this bookmark.");
            }
            throw error;
        }
        recordAudit(
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
                                ServiceSupport::report)
                        .orElseThrow();
        recordAudit(
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
                                ServiceSupport::bookmark)
                        .orElseThrow(StackverseProblem::notFound);
        if ("hidden".equals(bookmark.status())) {
            return;
        }
        execute(
                connection,
                "update bookmarks set status = 'hidden', updated_at = ? where id = ?",
                List.of(now(), bookmarkId));
        recordAudit(
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
}
