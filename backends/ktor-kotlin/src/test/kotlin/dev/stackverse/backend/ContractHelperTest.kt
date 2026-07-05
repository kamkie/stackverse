package dev.stackverse.backend

import com.fasterxml.jackson.module.kotlin.readValue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ContractHelperTest {
    @Test
    fun `identity returns only Stackverse application roles`() {
        val identity = Identity(
            username = "demo",
            name = "Demo User",
            email = "demo@example.com",
            roles = listOf("offline_access", "moderator", "uma_authorization", "admin"),
        )

        assertEquals(listOf("moderator", "admin"), identity.applicationRoles())
    }

    @Test
    fun `HTTP URL validation accepts only absolute http and https URLs with hosts`() {
        assertTrue(isHttpUrl("https://example.com/bookmarks/1"))
        assertTrue(isHttpUrl("http://localhost:8080/healthz"))

        assertFalse(isHttpUrl("ftp://example.com/file"))
        assertFalse(isHttpUrl("https:///missing-host"))
        assertFalse(isHttpUrl("/relative/path"))
        assertFalse(isHttpUrl("not a url"))
    }

    @Test
    fun `database enum conversion preserves wire contract spellings`() {
        assertEquals("BROKEN_LINK", "broken-link".dbValue())
        assertEquals("PUBLIC", "public".dbValue())

        assertEquals("broken-link", "BROKEN_LINK".wireValue())
        assertEquals("private", "PRIVATE".wireValue())
    }

    @Test
    fun `SQL like escaping keeps user filters literal`() {
        assertEquals("""100\%\\path\_""", escapeLike("""100%\path_"""))
    }

    @Test
    fun `page count rounds partial pages and keeps empty result sets empty`() {
        assertEquals(0, pages(total = 0, size = 20))
        assertEquals(1, pages(total = 1, size = 20))
        assertEquals(2, pages(total = 21, size = 20))
        assertEquals(5, pages(total = 100, size = 20))
    }

    @Test
    fun `JSON mapper ignores unknown request fields and omits null response properties`() {
        val mapper = jsonMapper()

        val request = mapper.readValue<BookmarkRequest>(
            """
            {
              "url": "https://example.com",
              "title": "Example",
              "unexpected": "ignored"
            }
            """.trimIndent(),
        )
        assertEquals("https://example.com", request.url)
        assertEquals("Example", request.title)

        val encoded = mapper.writeValueAsString(MessageRequest(key = "ui.example", language = "en", text = "Example"))
        assertFalse(encoded.contains("description"))
    }
}
