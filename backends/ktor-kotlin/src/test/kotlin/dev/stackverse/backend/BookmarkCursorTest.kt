package dev.stackverse.backend

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import io.ktor.http.HttpStatusCode
import java.time.Instant
import java.util.Base64
import java.util.UUID

class BookmarkCursorTest {
    @Test
    fun `cursor encodes and decodes a keyset position`() {
        val cursor = BookmarkCursor(Instant.parse("2026-07-05T10:15:30Z"), UUID.fromString("11111111-1111-1111-1111-111111111111"))

        assertEquals(cursor.createdAt, BookmarkCursor.decode(cursor.encode()).createdAt)
        assertEquals(cursor.id, BookmarkCursor.decode(cursor.encode()).id)
    }

    @Test
    fun `malformed cursors are rejected as bad requests`() {
        val problem = assertFailsWith<ApiProblem> {
            BookmarkCursor.decode("not-base64!")
        }

        assertEquals(HttpStatusCode.BadRequest, problem.status)
        assertEquals("The cursor is malformed or unresolvable.", problem.detail)
    }

    @Test
    fun `decoded cursor payload must contain an instant and UUID`() {
        val encoded = Base64.getUrlEncoder().withoutPadding()
            .encodeToString("not-an-instant|not-a-uuid".toByteArray(Charsets.UTF_8))

        val problem = assertFailsWith<ApiProblem> {
            BookmarkCursor.decode(encoded)
        }

        assertEquals(HttpStatusCode.BadRequest, problem.status)
        assertEquals("The cursor is malformed or unresolvable.", problem.detail)
    }
}
