package dev.stackverse.backend.moderation

import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.bookmark.Bookmark
import dev.stackverse.backend.bookmark.BookmarkRepository
import dev.stackverse.backend.bookmark.BookmarkStatus
import dev.stackverse.backend.bookmark.Visibility
import dev.stackverse.backend.common.ConflictProblem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.util.Optional
import java.util.UUID

class ModerationServiceTest {

    private val reportRepository = mock(ReportRepository::class.java)
    private val bookmarkRepository = mock(BookmarkRepository::class.java)
    private val auditService = mock(AuditService::class.java)
    private val service = ModerationService(reportRepository, bookmarkRepository, auditService)

    @Test
    fun `duplicate report constraint race maps to conflict without audit side effects`() {
        val bookmark = publicBookmark()
        `when`(bookmarkRepository.findById(bookmark.id)).thenReturn(Optional.of(bookmark))
        `when`(
            reportRepository.existsByBookmarkIdAndReporterAndStatus(
                bookmark.id,
                "reporter",
                ReportStatus.OPEN,
            ),
        ).thenReturn(false)
        `when`(reportRepository.saveAndFlush(any(Report::class.java)))
            .thenThrow(DataIntegrityViolationException("uq_reports_one_open_per_reporter"))

        val problem = assertThrows<ConflictProblem> {
            service.report("reporter", bookmark.id, ReportRequest(reason = "spam", comment = "context"))
        }

        assertThat(problem.detail).contains("already have an open report")
        verify(reportRepository).saveAndFlush(any(Report::class.java))
        verifyNoInteractions(auditService)
    }

    @Test
    fun `reopen constraint race maps to conflict without recording an audit entry`() {
        val report = resolvedReport()
        `when`(reportRepository.findForUpdateById(report.id)).thenReturn(report)
        `when`(
            reportRepository.existsByBookmarkIdAndReporterAndStatusAndIdNot(
                report.bookmarkId,
                report.reporter,
                ReportStatus.OPEN,
                report.id,
            ),
        ).thenReturn(false)
        doThrow(DataIntegrityViolationException("uq_reports_one_open_per_reporter"))
            .`when`(reportRepository).flush()

        val problem = assertThrows<ConflictProblem> {
            service.resolve("moderator", report.id, ReportResolutionRequest(resolution = "open", note = "ignored"))
        }

        assertThat(problem.detail).contains("another open report")
        verify(reportRepository).flush()
        verifyNoInteractions(auditService, bookmarkRepository)
    }

    private fun publicBookmark() = Bookmark(
        owner = "owner",
        url = "https://example.com",
        title = "Example",
        notes = null,
        tags = mutableSetOf("kotlin"),
        visibility = Visibility.PUBLIC,
        status = BookmarkStatus.ACTIVE,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )

    private fun resolvedReport() = Report(
        id = UUID.randomUUID(),
        bookmarkId = UUID.randomUUID(),
        reporter = "reporter",
        reason = ReportReason.SPAM,
        comment = "context",
        status = ReportStatus.DISMISSED,
        resolvedBy = "moderator",
        resolvedAt = Instant.parse("2026-07-01T00:00:00Z"),
        resolutionNote = "not actionable",
        createdAt = Instant.EPOCH,
    )
}
