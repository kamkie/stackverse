package dev.stackverse.backend.moderation

import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.bookmark.BookmarkService
import dev.stackverse.backend.config.EventLogger
import dev.stackverse.backend.message.MessageService
import dev.stackverse.backend.support.ApiError
import dev.stackverse.backend.support.Paging
import dev.stackverse.backend.support.ReportRows
import dev.stackverse.backend.support.TimeSource
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional

import java.sql.Timestamp

class ModerationService {
    JdbcTemplate jdbcTemplate
    TimeSource timeSource
    MessageService messageService
    BookmarkService bookmarkService
    AuditService auditService
    EventLogger eventLogger

    Map listMine(String username, Map params) {
        int page = Paging.page(params.page)
        int size = Paging.size(params.size)
        List args = [username]
        String statusClause = ""
        if (params.status) {
            statusClause = "and status = ?"
            args << params.status.toString()
        }
        Long total = jdbcTemplate.queryForObject("select count(*) from reports where reporter = ? ${statusClause}", Long, args as Object[])
        List pageArgs = args + [size, page * size]
        List items = jdbcTemplate.query("""
            select id, bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at
            from reports
            where reporter = ? ${statusClause}
            order by created_at desc, id desc
            limit ? offset ?
        """, { rs, rowNum -> ReportRows.row(rs) }, pageArgs as Object[])
        Paging.resultPage(items, page, size, total)
    }

    @Transactional
    Map updateMine(UUID id, Map input, String username, String explicitLang, String acceptLanguage) {
        Map existing = find(id)
        if (!existing || existing.reporter != username) {
            throw ApiError.notFound()
        }
        if (existing.status != "open") {
            throw ApiError.conflict("Only open reports can be changed.")
        }
        validateReportInput(input, explicitLang, acceptLanguage)
        jdbcTemplate.update("update reports set reason = ?, comment = ? where id = ?", input.reason, input.comment, id)
        eventLogger.info("report_updated", "success", "Report updated", [actor: username, resource_type: "report", resource_id: id.toString()])
        find(id)
    }

    @Transactional
    void withdraw(UUID id, String username) {
        Map existing = find(id)
        if (!existing || existing.reporter != username) {
            throw ApiError.notFound()
        }
        if (existing.status != "open") {
            throw ApiError.conflict("Only open reports can be withdrawn.")
        }
        jdbcTemplate.update("delete from reports where id = ?", id)
        eventLogger.info("report_withdrawn", "success", "Report withdrawn", [actor: username, resource_type: "report", resource_id: id.toString()])
    }

    Map listQueue(Map params) {
        int page = Paging.page(params.page)
        int size = Paging.size(params.size)
        String status = params.status ?: "open"
        List args = []
        String where = ""
        if (status) {
            where = "where status = ?"
            args << status
        }
        Long total = jdbcTemplate.queryForObject("select count(*) from reports ${where}", Long, args as Object[])
        String order = status == "open" ? "created_at asc, id asc" : "created_at desc, id desc"
        List pageArgs = args + [size, page * size]
        List items = jdbcTemplate.query("""
            select id, bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at
            from reports
            ${where}
            order by ${order}
            limit ? offset ?
        """, { rs, rowNum -> ReportRows.row(rs) }, pageArgs as Object[])
        Paging.resultPage(items, page, size, total)
    }

    @Transactional
    Map resolve(UUID id, Map input, String actor, String explicitLang, String acceptLanguage) {
        Map existing = find(id)
        if (!existing) {
            throw ApiError.notFound()
        }
        List errors = []
        String resolution = input.resolution
        if (!(resolution in ["open", "dismissed", "actioned"])) {
            errors << messageService.validationError("resolution", "validation.resolution.invalid", explicitLang, acceptLanguage)
        }
        if (input.note != null && input.note.toString().size() > 1000) {
            errors << messageService.validationError("note", "validation.resolution.note.too-long", explicitLang, acceptLanguage)
        }
        if (errors) {
            throw ApiError.badRequest("Validation failed.", errors)
        }
        if (resolution == "open") {
            jdbcTemplate.update("""
                update reports set status = 'open', resolved_by = null, resolved_at = null, resolution_note = null
                where id = ?
            """, id)
            auditService.record(actor, "report.reopened", "report", id.toString())
            eventLogger.info("report_reopened", "success", "Report reopened", [actor: actor, resource_type: "report", resource_id: id.toString()])
            return find(id)
        }

        def now = timeSource.now()
        jdbcTemplate.update("""
            update reports set status = ?, resolved_by = ?, resolved_at = ?, resolution_note = ?
            where id = ?
        """, resolution, actor, Timestamp.from(now), input.note, id)
        if (resolution == "actioned") {
            jdbcTemplate.update("update bookmarks set status = 'hidden', updated_at = ? where id = ?", Timestamp.from(now), UUID.fromString(existing.bookmarkId))
            jdbcTemplate.update("""
                update reports set status = 'actioned', resolved_by = ?, resolved_at = ?, resolution_note = ?
                where bookmark_id = ? and status = 'open' and id <> ?
            """, actor, Timestamp.from(now), input.note, UUID.fromString(existing.bookmarkId), id)
        }
        auditService.record(actor, "report.resolved", "report", id.toString(), [resolution: resolution])
        eventLogger.info("report_resolved", "success", "Report resolved", [actor: actor, resource_type: "report", resource_id: id.toString()])
        find(id)
    }

    @Transactional
    Map setBookmarkStatus(UUID id, Map input, String actor, String explicitLang, String acceptLanguage) {
        Map existing = bookmarkService.find(id)
        if (!existing) {
            throw ApiError.notFound()
        }
        List errors = []
        String status = input.status
        if (!(status in ["active", "hidden"])) {
            errors << messageService.validationError("status", "validation.bookmark-status.invalid", explicitLang, acceptLanguage)
        }
        if (input.note != null && input.note.toString().size() > 1000) {
            errors << messageService.validationError("note", "validation.bookmark-status.note.too-long", explicitLang, acceptLanguage)
        }
        if (errors) {
            throw ApiError.badRequest("Validation failed.", errors)
        }
        jdbcTemplate.update("update bookmarks set status = ?, updated_at = ? where id = ?", status, Timestamp.from(timeSource.now()), id)
        auditService.record(actor, "bookmark.status-changed", "bookmark", id.toString(), [status: status, note: input.note])
        eventLogger.info("bookmark_status_changed", "success", "Bookmark status changed", [actor: actor, resource_type: "bookmark", resource_id: id.toString()])
        bookmarkService.find(id)
    }

    Map find(UUID id) {
        List rows = jdbcTemplate.query("""
            select id, bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at
            from reports where id = ?
        """, { rs, rowNum -> ReportRows.row(rs) }, id)
        rows ? rows[0] : null
    }

    void validateReportInput(Map input, String explicitLang, String acceptLanguage) {
        List errors = []
        if (!(input.reason in ["spam", "offensive", "broken-link", "other"])) {
            errors << messageService.validationError("reason", "validation.report.reason.invalid", explicitLang, acceptLanguage)
        }
        if (input.comment != null && input.comment.toString().size() > 1000) {
            errors << messageService.validationError("comment", "validation.report.comment.too-long", explicitLang, acceptLanguage)
        }
        if (errors) {
            throw ApiError.badRequest("Validation failed.", errors)
        }
    }
}
