package dev.stackverse.backend.moderation

import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.bookmark.Bookmark
import dev.stackverse.backend.bookmark.BookmarkRepository
import dev.stackverse.backend.bookmark.BookmarkStatus
import dev.stackverse.backend.bookmark.Visibility
import dev.stackverse.backend.common.ConflictProblem
import dev.stackverse.backend.common.NotFoundProblem
import dev.stackverse.backend.common.Validator
import dev.stackverse.backend.common.logEvent
import dev.stackverse.backend.common.nowUtc
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.data.domain.Page
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

@Service
@Transactional
class ModerationService(
    private val reportRepository: ReportRepository,
    private val bookmarkRepository: BookmarkRepository,
    private val auditService: AuditService,
) {

    // moderation events are diagnostics only (docs/LOGGING.md §5) — the audit trail stays authoritative
    private val log = LoggerFactory.getLogger(javaClass)

    /** SPEC rule 13: only public, non-hidden bookmarks can be reported; anything else is a 404 mask. */
    fun report(reporter: String, bookmarkId: UUID, request: ReportRequest): Report {
        val bookmark = bookmarkRepository.findById(bookmarkId).orElseThrow { NotFoundProblem() }
        if (bookmark.visibility != Visibility.PUBLIC || bookmark.status != BookmarkStatus.ACTIVE) {
            throw NotFoundProblem()
        }

        val reason = validatedReason(request)

        if (reportRepository.existsByBookmarkIdAndReporterAndStatus(bookmarkId, reporter, ReportStatus.OPEN)) {
            throw ConflictProblem("You already have an open report on this bookmark.")
        }
        return try {
            val report = reportRepository.saveAndFlush(
                Report(
                    bookmarkId = bookmarkId,
                    reporter = reporter,
                    reason = reason,
                    comment = request.comment,
                    status = ReportStatus.OPEN,
                    resolvedBy = null,
                    resolvedAt = null,
                    resolutionNote = null,
                    createdAt = nowUtc(),
                ),
            )
            log.logEvent(
                Level.INFO, "report_created", "success", "Report created on a public bookmark",
                "actor" to reporter,
                "resource_type" to "report",
                "resource_id" to report.id.toString(),
                "bookmark_id" to bookmarkId.toString(),
                "reason" to report.reason.wire,
            )
            report
        } catch (e: DataIntegrityViolationException) {
            // lost the race against a concurrent report by the same user — same outcome as the pre-check
            throw ConflictProblem("You already have an open report on this bookmark.")
        }
    }

    @Transactional(readOnly = true)
    fun listReports(status: ReportStatus, page: Int, size: Int): Page<Report> =
        reportRepository.findByStatus(status, PageRequest.of(page, size, Sort.by("createdAt", "id")))

    /** SPEC rule 13: the reporter's own feedback loop — their reports, newest first. */
    @Transactional(readOnly = true)
    fun listMyReports(reporter: String, status: ReportStatus?, page: Int, size: Int): Page<Report> {
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"))
        return if (status == null) {
            reportRepository.findByReporter(reporter, pageable)
        } else {
            reportRepository.findByReporterAndStatus(reporter, status, pageable)
        }
    }

    /** SPEC rule 13: the reporter may revise reason/comment while the report is open. */
    fun updateMyReport(reporter: String, reportId: UUID, request: ReportRequest): Report {
        val report = ownReport(reporter, reportId)
        val reason = validatedReason(request)
        requireOpen(report)
        report.reason = reason
        report.comment = request.comment
        log.logEvent(
            Level.INFO, "report_updated", "success", "Report updated by its reporter",
            "actor" to reporter,
            "resource_type" to "report",
            "resource_id" to report.id.toString(),
            "bookmark_id" to report.bookmarkId.toString(),
            "reason" to report.reason.wire,
        )
        return report
    }

    /** SPEC rule 13: withdrawing removes the report and frees the one-open-report slot. */
    fun withdraw(reporter: String, reportId: UUID) {
        val report = ownReport(reporter, reportId)
        requireOpen(report)
        reportRepository.delete(report)
        log.logEvent(
            Level.INFO, "report_withdrawn", "success", "Report withdrawn by its reporter",
            "actor" to reporter,
            "resource_type" to "report",
            "resource_id" to report.id.toString(),
            "bookmark_id" to report.bookmarkId.toString(),
        )
    }

    /** Someone else's report is a 404 mask — existence is not disclosed. */
    private fun ownReport(reporter: String, reportId: UUID): Report {
        val report = reportRepository.findForUpdateById(reportId) ?: throw NotFoundProblem()
        if (report.reporter != reporter) throw NotFoundProblem()
        return report
    }

    private fun requireOpen(report: Report) {
        if (report.status != ReportStatus.OPEN) {
            throw ConflictProblem("The report has already been resolved.")
        }
    }

    private fun validatedReason(request: ReportRequest): ReportReason {
        val validator = Validator()
        val reason = ReportReason.fromWire(request.reason)
        if (reason == null) {
            validator.reject("reason", "validation.report.reason.invalid")
        }
        validator.check((request.comment?.length ?: 0) <= 1000, "comment", "validation.report.comment.too-long")
        validator.throwIfInvalid()
        return reason!!
    }

    /**
     * SPEC rule 14: `actioned` hides the bookmark and drags every sibling open
     * report along. Decisions are revisable — any target status is accepted;
     * `open` re-opens the report and clears the resolution fields. Moving away
     * from `actioned` never restores the bookmark (rule 15 keeps hide/restore
     * explicit).
     */
    fun resolve(actor: String, reportId: UUID, request: ReportResolutionRequest): Report {
        val validator = Validator()
        val resolution = when (request.resolution) {
            "open" -> ReportStatus.OPEN
            "dismissed" -> ReportStatus.DISMISSED
            "actioned" -> ReportStatus.ACTIONED
            else -> {
                validator.reject("resolution", "validation.resolution.invalid")
                null
            }
        }
        validator.check((request.note?.length ?: 0) <= 1000, "note", "validation.resolution.note.too-long")
        validator.throwIfInvalid()

        val report = reportRepository.findForUpdateById(reportId) ?: throw NotFoundProblem()

        if (resolution == ReportStatus.OPEN) {
            reopenOne(report, actor)
            return report
        }

        resolveOne(report, resolution!!, actor, request.note, autoResolved = false)

        if (resolution == ReportStatus.ACTIONED) {
            hideBookmark(actor, report.bookmarkId, request.note)
            reportRepository.findByBookmarkIdAndStatus(report.bookmarkId, ReportStatus.OPEN)
                .filter { it.id != report.id }
                .forEach { resolveOne(it, ReportStatus.ACTIONED, actor, request.note, autoResolved = true) }
        }
        return report
    }

    private fun reopenOne(report: Report, actor: String) {
        report.status = ReportStatus.OPEN
        report.resolvedBy = null
        report.resolvedAt = null
        report.resolutionNote = null
        auditService.record(
            actor,
            "report.reopened",
            "report",
            report.id.toString(),
            mapOf("bookmarkId" to report.bookmarkId.toString()),
        )
        log.logEvent(
            Level.INFO, "report_reopened", "success", "Report re-opened",
            "actor" to actor,
            "resource_type" to "report",
            "resource_id" to report.id.toString(),
            "bookmark_id" to report.bookmarkId.toString(),
        )
    }

    /** SPEC rule 15: hide/restore switches `status` only; `visibility` is never touched. */
    fun setBookmarkStatus(actor: String, bookmarkId: UUID, request: BookmarkStatusRequest): Bookmark {
        val validator = Validator()
        validator.check(request.status != null, "status", "validation.bookmark-status.invalid")
        validator.check((request.note?.length ?: 0) <= 1000, "note", "validation.bookmark-status.note.too-long")
        validator.throwIfInvalid()
        val status = requireNotNull(request.status)

        val bookmark = bookmarkRepository.findById(bookmarkId).orElseThrow { NotFoundProblem() }
        val previous = bookmark.status
        bookmark.status = status
        bookmark.updatedAt = nowUtc()
        auditService.record(
            actor,
            "bookmark.status-changed",
            "bookmark",
            bookmark.id.toString(),
            mapOf("from" to previous.wire, "to" to status.wire, "note" to request.note),
        )
        log.logEvent(
            Level.INFO, "bookmark_status_changed", "success", "Bookmark moderation status changed",
            "actor" to actor,
            "resource_type" to "bookmark",
            "resource_id" to bookmark.id.toString(),
            "from" to previous.wire,
            "to" to status.wire,
        )
        return bookmark
    }

    private fun resolveOne(report: Report, resolution: ReportStatus, actor: String, note: String?, autoResolved: Boolean) {
        report.status = resolution
        report.resolvedBy = actor
        report.resolvedAt = nowUtc()
        report.resolutionNote = note
        auditService.record(
            actor,
            "report.resolved",
            "report",
            report.id.toString(),
            mapOf(
                "bookmarkId" to report.bookmarkId.toString(),
                "resolution" to resolution.wire,
                "note" to note,
                "autoResolved" to autoResolved,
            ),
        )
        log.logEvent(
            Level.INFO, "report_resolved", "success", "Report resolved",
            "actor" to actor,
            "resource_type" to "report",
            "resource_id" to report.id.toString(),
            "bookmark_id" to report.bookmarkId.toString(),
            "resolution" to resolution.wire,
            "auto_resolved" to autoResolved,
        )
    }

    private fun hideBookmark(actor: String, bookmarkId: UUID, note: String?) {
        val bookmark = bookmarkRepository.findById(bookmarkId).orElseThrow { NotFoundProblem() }
        if (bookmark.status != BookmarkStatus.HIDDEN) {
            bookmark.status = BookmarkStatus.HIDDEN
            bookmark.updatedAt = nowUtc()
            auditService.record(
                actor,
                "bookmark.status-changed",
                "bookmark",
                bookmark.id.toString(),
                mapOf("from" to BookmarkStatus.ACTIVE.wire, "to" to BookmarkStatus.HIDDEN.wire, "note" to note),
            )
            log.logEvent(
                Level.INFO, "bookmark_status_changed", "success", "Bookmark hidden by an actioned report",
                "actor" to actor,
                "resource_type" to "bookmark",
                "resource_id" to bookmark.id.toString(),
                "from" to BookmarkStatus.ACTIVE.wire,
                "to" to BookmarkStatus.HIDDEN.wire,
            )
        }
    }
}
