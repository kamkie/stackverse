package dev.stackverse.backend

import dev.stackverse.backend.bookmark.BookmarkRepository
import org.hamcrest.Matchers.containsInAnyOrder
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper

class BookmarkApiTest : IntegrationTest() {

    @Autowired
    lateinit var bookmarkRepository: BookmarkRepository

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun cleanBookmarks() {
        bookmarkRepository.deleteAll()
    }

    private fun createBookmark(owner: String, body: String): String {
        val response = mockMvc.perform(
            post("/api/v1/bookmarks").with(user(owner)).contentType(MediaType.APPLICATION_JSON).content(body),
        )
            .andExpect(status().isCreated)
            .andExpect(header().string("Location", containsString("/api/v1/bookmarks/")))
            .andReturn().response.contentAsString
        return objectMapper.readTree(response).get("id").asString()
    }

    @Test
    fun `create applies defaults and normalizes tags`() {
        mockMvc.perform(
            post("/api/v1/bookmarks").with(user("alice")).contentType(MediaType.APPLICATION_JSON)
                .content("""{"url":"https://example.com","title":"Example","tags":[" Kotlin ","jvm","kotlin"],"ignored":"field"}"""),
        )
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.owner").value("alice"))
            .andExpect(jsonPath("$.visibility").value("private"))
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.tags", containsInAnyOrder("kotlin", "jvm")))
            .andExpect(jsonPath("$.notes").doesNotExist())
    }

    @Test
    fun `validation failures list localized field errors`() {
        mockMvc.perform(
            post("/api/v1/bookmarks").with(user("alice")).contentType(MediaType.APPLICATION_JSON)
                .header("Accept-Language", "pl")
                .content("""{"url":"not a url","title":"","tags":["UPPER CASE!!"],"notes":"${"x".repeat(4001)}"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(header().string("Content-Type", containsString("application/problem+json")))
            .andExpect(jsonPath("$.errors[?(@.field=='url')].messageKey").value("validation.url.invalid"))
            .andExpect(jsonPath("$.errors[?(@.field=='url')].message").value("Adres URL musi być poprawnym adresem http(s)."))
            .andExpect(jsonPath("$.errors[?(@.field=='title')].messageKey").value("validation.title.required"))
            .andExpect(jsonPath("$.errors[?(@.field=='tags')].messageKey").value("validation.tag.invalid"))
            .andExpect(jsonPath("$.errors[?(@.field=='notes')].messageKey").value("validation.notes.too-long"))
    }

    @Test
    fun `owners always read their bookmarks, others only public ones`() {
        val privateId = createBookmark("alice", """{"url":"https://example.com/p","title":"private"}""")
        val publicId = createBookmark("alice", """{"url":"https://example.com/q","title":"public","visibility":"public"}""")

        mockMvc.perform(get("/api/v1/bookmarks/{id}", privateId).with(user("alice"))).andExpect(status().isOk)
        // rule 1: not 403 — existence is not disclosed
        mockMvc.perform(get("/api/v1/bookmarks/{id}", privateId).with(user("bob"))).andExpect(status().isNotFound)
        mockMvc.perform(get("/api/v1/bookmarks/{id}", publicId).with(user("bob"))).andExpect(status().isOk)
        mockMvc.perform(get("/api/v1/bookmarks/{id}", publicId)).andExpect(status().isOk)
    }

    @Test
    fun `update and delete are owner-only and masked as 404`() {
        val id = createBookmark("alice", """{"url":"https://example.com","title":"mine","visibility":"public"}""")
        val update = """{"url":"https://example.com","title":"changed"}"""

        mockMvc.perform(put("/api/v1/bookmarks/{id}", id).with(user("bob")).contentType(MediaType.APPLICATION_JSON).content(update))
            .andExpect(status().isNotFound)
        mockMvc.perform(delete("/api/v1/bookmarks/{id}", id).with(user("bob"))).andExpect(status().isNotFound)

        mockMvc.perform(put("/api/v1/bookmarks/{id}", id).with(user("alice")).contentType(MediaType.APPLICATION_JSON).content(update))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.title").value("changed"))
            .andExpect(jsonPath("$.visibility").value("private"))

        mockMvc.perform(delete("/api/v1/bookmarks/{id}", id).with(user("alice"))).andExpect(status().isNoContent)
        mockMvc.perform(get("/api/v1/bookmarks/{id}", id).with(user("alice"))).andExpect(status().isNotFound)
    }

    @Test
    fun `v1 listing returns own bookmarks newest first with filters`() {
        createBookmark("alice", """{"url":"https://example.com/1","title":"kotlin post","tags":["kotlin","jvm"]}""")
        createBookmark("alice", """{"url":"https://example.com/2","title":"go post","notes":"about golang","tags":["go"]}""")
        createBookmark("bob", """{"url":"https://example.com/3","title":"bobs","tags":["kotlin"]}""")

        mockMvc.perform(get("/api/v1/bookmarks").with(user("alice")))
            .andExpect(status().isOk)
            .andExpect(header().string("Deprecation", "@1782864000"))
            .andExpect(header().string("Sunset", "Thu, 01 Jul 2027 00:00:00 GMT"))
            .andExpect(header().string("Link", """</api/v2/bookmarks>; rel="successor-version""""))
            .andExpect(jsonPath("$.items", hasSize<Any>(2)))
            .andExpect(jsonPath("$.items[0].title").value("go post"))
            .andExpect(jsonPath("$.totalItems").value(2))
            .andExpect(jsonPath("$.totalPages").value(1))

        mockMvc.perform(get("/api/v1/bookmarks").param("tag", "kotlin").param("tag", "jvm").with(user("alice")))
            .andExpect(jsonPath("$.items", hasSize<Any>(1)))
            .andExpect(jsonPath("$.items[0].title").value("kotlin post"))

        mockMvc.perform(get("/api/v1/bookmarks").param("q", "GOLANG").with(user("alice")))
            .andExpect(jsonPath("$.items", hasSize<Any>(1)))
            .andExpect(jsonPath("$.items[0].title").value("go post"))
    }

    @Test
    fun `public feed spans owners, requires no auth, and hides nothing private`() {
        createBookmark("alice", """{"url":"https://example.com/1","title":"alice pub","visibility":"public"}""")
        createBookmark("bob", """{"url":"https://example.com/2","title":"bob priv"}""")

        mockMvc.perform(get("/api/v1/bookmarks").param("visibility", "public"))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items", hasSize<Any>(1)))
            .andExpect(jsonPath("$.items[0].title").value("alice pub"))

        // anonymous caller asking for anything but the public feed → 401
        mockMvc.perform(get("/api/v1/bookmarks")).andExpect(status().isUnauthorized)
        mockMvc.perform(get("/api/v2/bookmarks")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `v2 keyset pagination is stable under concurrent inserts`() {
        repeat(5) { createBookmark("alice", """{"url":"https://example.com/$it","title":"item $it"}""") }

        val firstPage = mockMvc.perform(get("/api/v2/bookmarks").param("size", "2").with(user("alice")))
            .andExpect(status().isOk)
            .andReturn().response.contentAsString
        val firstJson = objectMapper.readTree(firstPage)
        val cursor = firstJson.get("nextCursor").asString()

        // a concurrent insert lands before the cursor position and must not shift page 2
        createBookmark("alice", """{"url":"https://example.com/new","title":"concurrent"}""")

        val seen = mutableListOf<String>()
        firstJson.get("items").forEach { seen += it.get("id").asString() }
        var next: String? = cursor
        while (next != null) {
            val body = mockMvc.perform(get("/api/v2/bookmarks").param("size", "2").param("cursor", next).with(user("alice")))
                .andExpect(status().isOk)
                .andReturn().response.contentAsString
            val json = objectMapper.readTree(body)
            json.get("items").forEach { seen += it.get("id").asString() }
            next = json.get("nextCursor")?.asString()
        }

        // no duplicates, no skips: exactly the 5 pre-cursor items, each once
        assert(seen.size == 5) { "expected 5 items, got $seen" }
        assert(seen.toSet().size == 5) { "duplicate items in $seen" }
    }

    @Test
    fun `malformed cursor is a 400 problem`() {
        mockMvc.perform(get("/api/v2/bookmarks").param("cursor", "not-a-cursor").with(user("alice")))
            .andExpect(status().isBadRequest)
            .andExpect(header().string("Content-Type", containsString("application/problem+json")))
    }

    @Test
    fun `page size is capped at 100`() {
        mockMvc.perform(get("/api/v1/bookmarks").param("size", "101").with(user("alice"))).andExpect(status().isBadRequest)
        mockMvc.perform(get("/api/v2/bookmarks").param("size", "0").with(user("alice"))).andExpect(status().isBadRequest)
    }

    @Test
    fun `tags endpoint counts own tags most used first`() {
        createBookmark("alice", """{"url":"https://example.com/1","title":"a","tags":["kotlin"]}""")
        createBookmark("alice", """{"url":"https://example.com/2","title":"b","tags":["kotlin","jvm"]}""")
        createBookmark("bob", """{"url":"https://example.com/3","title":"c","tags":["kotlin"]}""")

        mockMvc.perform(get("/api/v1/tags").with(user("alice")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.tags[0].tag").value("kotlin"))
            .andExpect(jsonPath("$.tags[0].count").value(2))
            .andExpect(jsonPath("$.tags[1].tag").value("jvm"))

        mockMvc.perform(get("/api/v1/tags")).andExpect(status().isUnauthorized)
    }
}
