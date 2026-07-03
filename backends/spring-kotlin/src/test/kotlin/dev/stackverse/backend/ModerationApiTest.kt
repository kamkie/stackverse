package dev.stackverse.backend

import dev.stackverse.backend.bookmark.BookmarkRepository
import dev.stackverse.backend.moderation.ReportRepository
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper

class ModerationApiTest : IntegrationTest() {

    @Autowired
    lateinit var bookmarkRepository: BookmarkRepository

    @Autowired
    lateinit var reportRepository: ReportRepository

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun clean() {
        reportRepository.deleteAll()
        bookmarkRepository.deleteAll()
    }

    private fun createBookmark(owner: String, visibility: String = "public"): String {
        val body = mockMvc.perform(
            post("/api/v1/bookmarks").with(user(owner)).contentType(MediaType.APPLICATION_JSON)
                .content("""{"url":"https://example.com","title":"reportable","visibility":"$visibility"}"""),
        ).andExpect(status().isCreated).andReturn().response.contentAsString
        return objectMapper.readTree(body).get("id").asString()
    }

    private fun report(reporter: String, bookmarkId: String, reason: String = "spam") =
        mockMvc.perform(
            post("/api/v1/bookmarks/{id}/reports", bookmarkId).with(user(reporter))
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"$reason"}"""),
        )

    @Test
    fun `reporting works only for public active bookmarks`() {
        val publicId = createBookmark("alice")
        val privateId = createBookmark("alice", "private")

        report("bob", publicId)
            .andExpect(status().isCreated)
            .andExpect(jsonPath("$.reporter").value("bob"))
            .andExpect(jsonPath("$.status").value("open"))

        // private → 404 mask, even for the owner
        report("bob", privateId).andExpect(status().isNotFound)
        report("alice", privateId).andExpect(status().isNotFound)

        // second open report by the same user → 409
        report("bob", publicId, reason = "other").andExpect(status().isConflict)

        // invalid reason → localized field error
        mockMvc.perform(
            post("/api/v1/bookmarks/{id}/reports", publicId).with(user("carol"))
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"dislike"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].messageKey").value("validation.report.reason.invalid"))

        // reporting requires authentication
        mockMvc.perform(
            post("/api/v1/bookmarks/{id}/reports", publicId)
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"spam"}"""),
        ).andExpect(status().isUnauthorized)
    }

    private fun reportId(reporter: String, bookmarkId: String, reason: String = "spam"): String =
        objectMapper.readTree(report(reporter, bookmarkId, reason).andReturn().response.contentAsString)
            .get("id").asString()

    private fun resolve(reportId: String, resolution: String = "dismissed") {
        mockMvc.perform(
            put("/api/v1/admin/reports/{id}", reportId).with(moderator())
                .contentType(MediaType.APPLICATION_JSON).content("""{"resolution":"$resolution"}"""),
        ).andExpect(status().isOk)
    }

    @Test
    fun `reporters list only their own reports, newest first, with a status filter`() {
        val first = createBookmark("alice")
        val second = createBookmark("alice")
        val bobFirst = reportId("bob", first)
        report("bob", second).andExpect(status().isCreated)
        report("carol", first).andExpect(status().isCreated)

        mockMvc.perform(get("/api/v1/reports")).andExpect(status().isUnauthorized)

        mockMvc.perform(get("/api/v1/reports").with(user("bob")))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items", hasSize<Any>(2)))
            .andExpect(jsonPath("$.items[0].bookmarkId").value(second))
            .andExpect(jsonPath("$.items[1].bookmarkId").value(first))

        resolve(bobFirst, "dismissed")
        mockMvc.perform(get("/api/v1/reports").param("status", "open").with(user("bob")))
            .andExpect(jsonPath("$.items", hasSize<Any>(1)))
            .andExpect(jsonPath("$.items[0].bookmarkId").value(second))
        mockMvc.perform(get("/api/v1/reports").param("status", "dismissed").with(user("bob")))
            .andExpect(jsonPath("$.items", hasSize<Any>(1)))
            .andExpect(jsonPath("$.items[0].resolvedBy").value("moderator"))
    }

    @Test
    fun `a reporter may revise their own open report only`() {
        val bookmarkId = createBookmark("alice")
        val id = reportId("bob", bookmarkId, reason = "spam")

        mockMvc.perform(
            put("/api/v1/reports/{id}", id).with(user("bob"))
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"other","comment":"on second thought"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.reason").value("other"))
            .andExpect(jsonPath("$.comment").value("on second thought"))
            .andExpect(jsonPath("$.status").value("open"))

        // someone else's report is a 404 mask
        mockMvc.perform(
            put("/api/v1/reports/{id}", id).with(user("carol"))
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"spam"}"""),
        ).andExpect(status().isNotFound)

        // invalid reason → localized field error
        mockMvc.perform(
            put("/api/v1/reports/{id}", id).with(user("bob"))
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"dislike"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].messageKey").value("validation.report.reason.invalid"))

        // resolved reports are frozen
        resolve(id)
        mockMvc.perform(
            put("/api/v1/reports/{id}", id).with(user("bob"))
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"spam"}"""),
        ).andExpect(status().isConflict)
    }

    @Test
    fun `withdrawing removes the report and frees the open slot`() {
        val bookmarkId = createBookmark("alice")
        val id = reportId("bob", bookmarkId)

        // someone else's report is a 404 mask
        mockMvc.perform(delete("/api/v1/reports/{id}", id).with(user("carol"))).andExpect(status().isNotFound)

        mockMvc.perform(delete("/api/v1/reports/{id}", id).with(user("bob"))).andExpect(status().isNoContent)
        mockMvc.perform(get("/api/v1/reports").with(user("bob")))
            .andExpect(jsonPath("$.items", hasSize<Any>(0)))
        mockMvc.perform(get("/api/v1/admin/reports").with(moderator()))
            .andExpect(jsonPath("$.items", hasSize<Any>(0)))

        // a withdrawn report no longer blocks a new one
        val again = reportId("bob", bookmarkId)

        // resolved reports cannot be withdrawn
        resolve(again)
        mockMvc.perform(delete("/api/v1/reports/{id}", again).with(user("bob"))).andExpect(status().isConflict)
    }

    @Test
    fun `queue is moderator-only, oldest first, defaulting to open`() {
        val id = createBookmark("alice")
        report("bob", id).andExpect(status().isCreated)
        report("carol", id).andExpect(status().isCreated)

        mockMvc.perform(get("/api/v1/admin/reports").with(user("alice"))).andExpect(status().isForbidden)
        mockMvc.perform(get("/api/v1/admin/reports")).andExpect(status().isUnauthorized)

        mockMvc.perform(get("/api/v1/admin/reports").with(moderator()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items", hasSize<Any>(2)))
            .andExpect(jsonPath("$.items[0].reporter").value("bob"))
            .andExpect(jsonPath("$.items[1].reporter").value("carol"))
    }

    @Test
    fun `dismissing a report leaves the bookmark alone`() {
        val bookmarkId = createBookmark("alice")
        val reportId = objectMapper.readTree(
            report("bob", bookmarkId).andReturn().response.contentAsString,
        ).get("id").asString()

        mockMvc.perform(
            put("/api/v1/admin/reports/{id}", reportId).with(moderator())
                .contentType(MediaType.APPLICATION_JSON).content("""{"resolution":"dismissed","note":"fine"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("dismissed"))
            .andExpect(jsonPath("$.resolvedBy").value("moderator"))
            .andExpect(jsonPath("$.resolutionNote").value("fine"))

        mockMvc.perform(get("/api/v1/bookmarks/{id}", bookmarkId).with(user("alice")))
            .andExpect(jsonPath("$.status").value("active"))

        // a new report is allowed once the previous one is resolved
        report("bob", bookmarkId).andExpect(status().isCreated)
    }

    @Test
    fun `actioning hides the bookmark and auto-resolves sibling reports`() {
        val bookmarkId = createBookmark("alice")
        val first = objectMapper.readTree(report("bob", bookmarkId).andReturn().response.contentAsString).get("id").asString()
        report("carol", bookmarkId).andExpect(status().isCreated)

        mockMvc.perform(
            put("/api/v1/admin/reports/{id}", first).with(moderator())
                .contentType(MediaType.APPLICATION_JSON).content("""{"resolution":"actioned","note":"spammy"}"""),
        ).andExpect(status().isOk)

        // sibling auto-resolved with the same resolver and note (SPEC rule 14)
        mockMvc.perform(get("/api/v1/admin/reports").param("status", "actioned").with(moderator()))
            .andExpect(jsonPath("$.items", hasSize<Any>(2)))
            .andExpect(jsonPath("$.items[1].reporter").value("carol"))
            .andExpect(jsonPath("$.items[1].resolvedBy").value("moderator"))
            .andExpect(jsonPath("$.items[1].resolutionNote").value("spammy"))
        mockMvc.perform(get("/api/v1/admin/reports").with(moderator()))
            .andExpect(jsonPath("$.items", hasSize<Any>(0)))

        // hidden: gone from the public feed and from non-owner reads, owner still sees it
        mockMvc.perform(get("/api/v2/bookmarks").param("visibility", "public"))
            .andExpect(jsonPath("$.items", hasSize<Any>(0)))
        mockMvc.perform(get("/api/v1/bookmarks/{id}", bookmarkId).with(user("bob"))).andExpect(status().isNotFound)
        mockMvc.perform(get("/api/v1/bookmarks/{id}", bookmarkId).with(user("alice")))
            .andExpect(jsonPath("$.status").value("hidden"))

        // resolving a non-open report → 409
        mockMvc.perform(
            put("/api/v1/admin/reports/{id}", first).with(moderator())
                .contentType(MediaType.APPLICATION_JSON).content("""{"resolution":"dismissed"}"""),
        ).andExpect(status().isConflict)
    }

    @Test
    fun `invalid resolution yields a localized field error`() {
        val bookmarkId = createBookmark("alice")
        val reportId = objectMapper.readTree(report("bob", bookmarkId).andReturn().response.contentAsString)
            .get("id").asString()

        mockMvc.perform(
            put("/api/v1/admin/reports/{id}", reportId).with(moderator())
                .contentType(MediaType.APPLICATION_JSON).content("""{"resolution":"ignore"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].messageKey").value("validation.resolution.invalid"))
            .andExpect(jsonPath("$.errors[0].message").value("Resolution must be one of: dismissed, actioned."))

        mockMvc.perform(
            put("/api/v1/admin/reports/{id}", reportId).with(moderator())
                .header("Accept-Language", "pl")
                .contentType(MediaType.APPLICATION_JSON).content("""{"resolution":"ignore"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].message").value("Rozstrzygnięcie musi być jednym z: dismissed, actioned."))
    }

    @Test
    fun `hidden bookmarks cannot be republished but can be edited and restored`() {
        val bookmarkId = createBookmark("alice")
        mockMvc.perform(
            put("/api/v1/admin/bookmarks/{id}/status", bookmarkId).with(moderator())
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"hidden","note":"manual"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("hidden"))

        // owner update keeping visibility public → 409 (rule 15)
        mockMvc.perform(
            put("/api/v1/bookmarks/{id}", bookmarkId).with(user("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"url":"https://example.com","title":"edited","visibility":"public"}"""),
        ).andExpect(status().isConflict)

        // owner may still edit while private
        mockMvc.perform(
            put("/api/v1/bookmarks/{id}", bookmarkId).with(user("alice"))
                .contentType(MediaType.APPLICATION_JSON)
                .content("""{"url":"https://example.com","title":"edited"}"""),
        ).andExpect(status().isOk)

        // restore sets active and leaves visibility untouched
        mockMvc.perform(
            put("/api/v1/admin/bookmarks/{id}/status", bookmarkId).with(moderator())
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"active"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.visibility").value("private"))

        // moderator role required
        mockMvc.perform(
            put("/api/v1/admin/bookmarks/{id}/status", bookmarkId).with(user("alice"))
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"hidden"}"""),
        ).andExpect(status().isForbidden)
    }
}
