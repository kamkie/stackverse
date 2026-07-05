package dev.stackverse.backend.bookmark

import dev.stackverse.backend.common.BadRequestProblem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.Instant
import java.util.UUID

class BookmarkCursorTest {

    @Test
    fun `cursor round-trips as opaque base64url without padding`() {
        val cursor = BookmarkCursor(
            createdAt = Instant.parse("2026-07-05T12:34:56.123456Z"),
            id = UUID.fromString("018f57c2-0d0f-7b55-bc80-08f62c3f3b8a"),
        )

        val encoded = cursor.encode()

        assertThat(encoded).doesNotContain("|", "=")
        assertThat(BookmarkCursor.decode(encoded)).isEqualTo(cursor)
    }

    @Test
    fun `cursor can be created from bookmark keyset position`() {
        val bookmark = bookmark(
            id = UUID.fromString("018f57c2-0d0f-7b55-bc80-08f62c3f3b8a"),
            createdAt = Instant.parse("2026-07-05T12:34:56.123456Z"),
        )

        assertThat(BookmarkCursor.of(bookmark)).isEqualTo(BookmarkCursor(bookmark.createdAt, bookmark.id))
    }

    @Test
    fun `malformed cursor is a bad request problem`() {
        val problem = assertThrows<BadRequestProblem> {
            BookmarkCursor.decode("not-a-valid-cursor")
        }

        assertThat(problem.detail).isEqualTo("The cursor is malformed or unresolvable.")
    }

    private fun bookmark(
        id: UUID = UUID.randomUUID(),
        createdAt: Instant = Instant.parse("2026-07-05T12:00:00Z"),
    ) = Bookmark(
        id = id,
        owner = "alice",
        url = "https://example.com",
        title = "Example",
        notes = null,
        tags = mutableSetOf("kotlin"),
        visibility = Visibility.PUBLIC,
        status = BookmarkStatus.ACTIVE,
        createdAt = createdAt,
        updatedAt = createdAt,
    )
}
