package dev.stackverse.backend.moderation

import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.bookmark.BookmarkService
import dev.stackverse.backend.config.EventLogger
import dev.stackverse.backend.message.MessageService
import dev.stackverse.backend.persistence.Bookmark
import dev.stackverse.backend.persistence.Report
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
        def criteria = Report.where { reporter == username }
        if (params.status) {
            String statusValue = params.status.toString()
            criteria = criteria.where { status == statusValue }
        }
        Long total = criteria.count()
        List items = criteria.list(max: size, offset: page * size) {
            order('createdAt', 'desc')
            order('id', 'desc')
        }.collect { Report report -> reportMap(report) }
        Paging.resultPage(items, page, size, total)
    }

    @Transactional
    Map updateMine(UUID id, Map input, String username) {
        Report existing = Report.get(id)
        if (!existing || existing.reporter != username) {
            throw ApiError.notFound()
        }
        if (existing.status != 'open') {
            throw ApiError.conflict("Only open reports can be changed.")
        }
        existing.reason = input.reason
        existing.comment = input.comment
        existing.save(failOnError: true, flush: true)
        eventLogger.info("report_updated", "success", "Report updated", [actor: username, resource_type: "report", resource_id: id.toString()])
        find(id)
    }

    @Transactional
    void withdraw(UUID id, String username) {
        Report existing = Report.get(id)
        if (!existing || existing.reporter != username) {
            throw ApiError.notFound()
        }
        if (existing.status != "open") {
            throw ApiError.conflict("Only open reports can be withdrawn.")
        }
        existing.delete(flush: true)
        eventLogger.info("report_withdrawn", "success", "Report withdrawn", [actor: username, resource_type: "report", resource_id: id.toString()])
    }

    Map listQueue(Map params) {
        int page = Paging.page(params.page)
        int size = Paging.size(params.size)
        String queueStatus = params.status ?: "open"
        def criteria = Report.where { status == queueStatus }
        Long total = criteria.count()
        List items = criteria.list(max: size, offset: page * size) {
            order('createdAt', queueStatus == 'open' ? 'asc' : 'desc')
            order('id', queueStatus == 'open' ? 'asc' : 'desc')
        }.collect { Report report -> reportMap(report) }
        Paging.resultPage(items, page, size, total)
    }

    @Transactional
    Map resolve(UUID id, Map input, String actor) {
        Report report = Report.get(id)
        if (!report) {
            throw ApiError.notFound()
        }
        String resolution = input.resolution
        if (resolution == "open") {
            report.status = 'open'
            report.resolvedBy = null
            report.resolvedAt = null
            report.resolutionNote = null
            report.save(failOnError: true, flush: true)
            auditService.record(actor, "report.reopened", "report", id.toString())
            eventLogger.info("report_reopened", "success", "Report reopened", [actor: actor, resource_type: "report", resource_id: id.toString()])
            return reportMap(report)
        }

        def now = timeSource.now()
        report.status = resolution
        report.resolvedBy = actor
        report.resolvedAt = now
        report.resolutionNote = input.note
        report.save(failOnError: true, flush: true)
        if (resolution == "actioned") {
            Bookmark bookmark = Bookmark.get(report.bookmarkId)
            bookmark.status = 'hidden'
            bookmark.updatedAt = now
            bookmark.save(failOnError: true, flush: true)
            jdbcTemplate.update("""
                update reports set status = 'actioned', resolved_by = ?, resolved_at = ?, resolution_note = ?
                where bookmark_id = ? and status = 'open' and id <> ?
            """, actor, Timestamp.from(now), input.note, report.bookmarkId, id)
        }
        auditService.record(actor, "report.resolved", "report", id.toString(), [resolution: resolution])
        eventLogger.info("report_resolved", "success", "Report resolved", [actor: actor, resource_type: "report", resource_id: id.toString()])
        reportMap(report)
    }

    @Transactional
    Map setBookmarkStatus(UUID id, Map input, String actor) {
        Bookmark existing = Bookmark.get(id)
        if (!existing) {
            throw ApiError.notFound()
        }
        String status = input.status
        existing.status = status
        existing.updatedAt = timeSource.now()
        existing.save(failOnError: true, flush: true)
        auditService.record(actor, "bookmark.status-changed", "bookmark", id.toString(), [status: status, note: input.note])
        eventLogger.info("bookmark_status_changed", "success", "Bookmark status changed", [actor: actor, resource_type: "bookmark", resource_id: id.toString()])
        bookmarkService.find(id)
    }

    Map find(UUID id) {
        Report report = Report.get(id)
        report ? reportMap(report) : null
    }

    private static Map reportMap(Report report) {
        [
            id            : report.id.toString(),
            bookmarkId    : report.bookmarkId.toString(),
            reporter      : report.reporter,
            reason        : report.reason,
            comment       : report.comment,
            status        : report.status,
            resolvedBy    : report.resolvedBy,
            resolvedAt    : report.resolvedAt?.toString(),
            resolutionNote: report.resolutionNote,
            createdAt     : report.createdAt.toString()
        ]
    }

}
