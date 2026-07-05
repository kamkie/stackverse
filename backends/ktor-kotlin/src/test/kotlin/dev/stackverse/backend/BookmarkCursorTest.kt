package dev.stackverse.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import java.time.Instant
import java.util.UUID

class BookmarkCursorTest {
    @Test
    fun `cursor encodes and decodes a keyset position`() {
        val cursor = BookmarkCursor(Instant.parse("2026-07-05T10:15:30Z"), UUID.fromString("11111111-1111-1111-1111-111111111111"))

        assertEquals(cursor.createdAt, BookmarkCursor.decode(cursor.encode()).createdAt)
        assertEquals(cursor.id, BookmarkCursor.decode(cursor.encode()).id)
    }
}
