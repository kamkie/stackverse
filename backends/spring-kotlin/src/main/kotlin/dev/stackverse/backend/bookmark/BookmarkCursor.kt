package dev.stackverse.backend.bookmark

import dev.stackverse.backend.common.BadRequestProblem
import java.time.Instant
import java.util.Base64
import java.util.UUID

/**
 * Keyset position for the v2 listing: the `(createdAt, id)` of the last item on the
 * previous page, wrapped in base64url so clients treat it as opaque. Keyset pagination
 * is what makes v2 stable under concurrent inserts — new rows land before the cursor
 * position and cannot shift what the next page returns.
 */
data class BookmarkCursor(val createdAt: Instant, val id: UUID) {

    fun encode(): String =
        Base64.getUrlEncoder().withoutPadding().encodeToString("$createdAt|$id".toByteArray(Charsets.UTF_8))

    companion object {
        fun of(bookmark: Bookmark) = BookmarkCursor(bookmark.createdAt, bookmark.id)

        fun decode(cursor: String): BookmarkCursor = try {
            val decoded = String(Base64.getUrlDecoder().decode(cursor), Charsets.UTF_8)
            val (createdAt, id) = decoded.split('|', limit = 2).also { require(it.size == 2) }
            BookmarkCursor(Instant.parse(createdAt), UUID.fromString(id))
        } catch (e: Exception) {
            throw BadRequestProblem("The cursor is malformed or unresolvable.")
        }
    }
}
