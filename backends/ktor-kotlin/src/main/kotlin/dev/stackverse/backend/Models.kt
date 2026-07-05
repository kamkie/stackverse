package dev.stackverse.backend

import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class Identity(val username: String, val name: String?, val email: String?, val roles: List<String>) {
    fun applicationRoles(): List<String> = roles.filter { it == "moderator" || it == "admin" }
}

data class MeResponse(val username: String, val name: String?, val email: String?, val roles: List<String>)
data class BookmarkRequest(val url: String? = null, val title: String? = null, val notes: String? = null, val tags: List<String>? = null, val visibility: String? = null)
data class BookmarkResponse(
    val id: UUID,
    val url: String,
    val title: String,
    val notes: String?,
    val tags: List<String>,
    val visibility: String,
    val status: String,
    val owner: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)
data class BookmarkListQuery(val tags: List<String>, val q: String?, val visibility: String?)
data class BookmarkCursorPageResponse(val items: List<BookmarkResponse>, val nextCursor: String?)
data class MessageRequest(val key: String? = null, val language: String? = null, val text: String? = null, val description: String? = null)
data class MessageResponse(val id: UUID, val key: String, val language: String, val text: String, val description: String?, val createdAt: Instant, val updatedAt: Instant)
data class MessageBundleResponse(val language: String, val messages: Map<String, String>)
data class ReportRequest(val reason: String? = null, val comment: String? = null)
data class ReportResolutionRequest(val resolution: String? = null, val note: String? = null)
data class BookmarkStatusRequest(val status: String? = null, val note: String? = null)
data class ReportResponse(
    val id: UUID,
    val bookmarkId: UUID,
    val reporter: String,
    val reason: String,
    val comment: String?,
    val status: String,
    val resolvedBy: String?,
    val resolvedAt: Instant?,
    val resolutionNote: String?,
    val createdAt: Instant,
)
data class UserStatusRequest(val status: String? = null, val reason: String? = null)
data class UserAccountResponse(val username: String, val firstSeen: Instant, val lastSeen: Instant, val status: String, val blockedReason: String?, val bookmarkCount: Long)
data class AuditEntryResponse(val id: UUID, val actor: String, val action: String, val targetType: String, val targetId: String, val detail: Map<String, Any?>?, val createdAt: Instant)
data class AuditFilter(val actor: String?, val action: String?, val targetType: String?, val targetId: String?, val from: Instant?, val to: Instant?)
data class TagCountResponse(val tag: String, val count: Long)
data class TagListResponse(val tags: List<TagCountResponse>)
data class StatsTotals(val users: Long, val bookmarks: Long, val publicBookmarks: Long, val hiddenBookmarks: Long, val openReports: Long)
data class DailyStat(val date: LocalDate, val bookmarksCreated: Long, val activeUsers: Long)
data class AdminStatsResponse(val totals: StatsTotals, val daily: List<DailyStat>, val topTags: List<TagCountResponse>)
data class PageResponse<T>(val items: List<T>, val page: Int, val size: Int, val totalItems: Long, val totalPages: Int)
data class Problem(val title: String, val status: Int, val detail: String? = null, val type: String = "about:blank", val errors: List<FieldError>? = null)
data class FieldError(val field: String, val messageKey: String, val message: String)
data class FieldViolation(val field: String, val messageKey: String)
