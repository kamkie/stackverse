package dev.stackverse.backend.moderation

import com.fasterxml.jackson.annotation.JsonInclude
import dev.stackverse.backend.bookmark.BookmarkStatus
import java.time.Instant
import java.util.UUID

data class ReportRequest(val reason: String? = null, val comment: String? = null)

data class ReportResolutionRequest(val resolution: String? = null, val note: String? = null)

data class BookmarkStatusRequest(val status: BookmarkStatus? = null, val note: String? = null)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class ReportResponse(
    val id: UUID,
    val bookmarkId: UUID,
    val reporter: String,
    val reason: ReportReason,
    val comment: String?,
    val status: ReportStatus,
    val resolvedBy: String?,
    val resolvedAt: Instant?,
    val resolutionNote: String?,
    val createdAt: Instant,
) {
    companion object {
        fun of(report: Report) = ReportResponse(
            id = report.id,
            bookmarkId = report.bookmarkId,
            reporter = report.reporter,
            reason = report.reason,
            comment = report.comment,
            status = report.status,
            resolvedBy = report.resolvedBy,
            resolvedAt = report.resolvedAt,
            resolutionNote = report.resolutionNote,
            createdAt = report.createdAt,
        )
    }
}
