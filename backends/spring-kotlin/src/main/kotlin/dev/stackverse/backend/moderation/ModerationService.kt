package dev.stackverse.backend.moderation

import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.bookmark.Bookmark
import dev.stackverse.backend.bookmark.BookmarkRepository
import dev.stackverse.backend.bookmark.BookmarkStatus
import dev.stackverse.backend.bookmark.Visibility
import dev.stackverse.backend.common.ConflictProblem
import dev.stackverse.backend.common.NotFoundProblem
import dev.stackverse.backend.common.Validator
import dev.stackverse.backend.common.nowUtc
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

    /** SPEC rule 13: only public, non-hidden bookmarks can be reported; anything else is a 404 mask. */
    fun report(reporter: String, bookmarkId: UUID, request: ReportRequest): Report {
        val bookmark = bookmarkRepository.findById(bookmarkId).orElseThrow { NotFoundProblem() }
        if (bookmark.visibility != Visibility.PUBLIC || bookmark.status != BookmarkStatus.ACTIVE) {
            throw NotFoundProblem()
        }

        val validator = Validator()
        val reason = ReportReason.fromWire(request.reason)
        if (reason == null) {
            validator.reject("reason", "validation.report.reason.invalid")
        }
        validator.check((request.comment?.length ?: 0) <= 1000, "comment", "validation.report.comment.too-long")
        validator.throwIfInvalid()

        if (reportRepository.existsByBookmarkIdAndReporterAndStatus(bookmarkId, reporter, ReportStatus.OPEN)) {
            throw ConflictProblem("You already have an open report on this bookmark.")
        }
        return try {
            reportRepository.saveAndFlush(
                Report(
                    bookmarkId = bookmarkId,
                    reporter = reporter,
                    reason = reason!!,
                    comment = request.comment,
                    status = ReportStatus.OPEN,
                    resolvedBy = null,
                    resolvedAt = null,
                    resolutionNote = null,
                    createdAt = nowUtc(),
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            // lost the race against a concurrent report by the same user — same outcome as the pre-check
            throw ConflictProblem("You already have an open report on this bookmark.")
        }
    }

    @Transactional(readOnly = true)
    fun listReports(status: ReportStatus, page: Int, size: Int): Page<Report> =
        reportRepository.findByStatus(status, PageRequest.of(page, size, Sort.by("createdAt", "id")))

    /** SPEC rule 14: `actioned` hides the bookmark and drags every sibling open report along. */
    fun resolve(actor: String, reportId: UUID, request: ReportResolutionRequest): Report {
        val validator = Validator()
        val resolution = when (request.resolution) {
            "dismissed" -> ReportStatus.DISMISSED
            "actioned" -> ReportStatus.ACTIONED
            else -> {
                validator.reject("resolution", "validation.resolution.invalid")
                null
            }
        }
        validator.check((request.note?.length ?: 0) <= 1000, "note", "validation.resolution.note.too-long")
        validator.throwIfInvalid()

        val report = reportRepository.findById(reportId).orElseThrow { NotFoundProblem() }
        if (report.status != ReportStatus.OPEN) {
            throw ConflictProblem("The report has already been resolved.")
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
        }
    }
}
