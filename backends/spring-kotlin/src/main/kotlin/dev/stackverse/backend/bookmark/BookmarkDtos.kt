package dev.stackverse.backend.bookmark

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

data class BookmarkRequest(
    val url: String? = null,
    val title: String? = null,
    val notes: String? = null,
    val tags: List<String>? = null,
    val visibility: Visibility? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class BookmarkResponse(
    val id: UUID,
    val url: String,
    val title: String,
    val notes: String?,
    val tags: List<String>,
    val visibility: Visibility,
    val status: BookmarkStatus,
    val owner: String,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(bookmark: Bookmark) = BookmarkResponse(
            id = bookmark.id,
            url = bookmark.url,
            title = bookmark.title,
            notes = bookmark.notes,
            tags = bookmark.tags.sorted(),
            visibility = bookmark.visibility,
            status = bookmark.status,
            owner = bookmark.owner,
            createdAt = bookmark.createdAt,
            updatedAt = bookmark.updatedAt,
        )
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
data class BookmarkCursorPageResponse(
    val items: List<BookmarkResponse>,
    val nextCursor: String?,
)

data class TagCountResponse(val tag: String, val count: Long)

data class TagListResponse(val tags: List<TagCountResponse>)
