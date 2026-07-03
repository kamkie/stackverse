package dev.stackverse.backend

import dev.stackverse.backend.bookmark.BookmarkRepository
import dev.stackverse.backend.moderation.ReportRepository
import dev.stackverse.backend.moderation.ReportStatus
import org.assertj.core.api.Assertions.assertThat
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
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.Executors

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

        // decisions are revisable (rule 14): actioned → dismissed succeeds,
        // and the bookmark stays hidden — restore is an explicit action
        mockMvc.perform(
            put("/api/v1/admin/reports/{id}", first).with(moderator())
                .contentType(MediaType.APPLICATION_JSON).content("""{"resolution":"dismissed"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("dismissed"))
        mockMvc.perform(get("/api/v1/bookmarks/{id}", bookmarkId).with(user("alice")))
            .andExpect(jsonPath("$.status").value("hidden"))
    }

    /**
     * Regression: `actioned` writes the bookmark row and every sibling open
     * report, so two moderators resolving different reports of the same
     * bookmark used to acquire report→bookmark locks in opposite orders and
     * deadlock (PostgreSQL aborts one transaction → 500). The fix locks the
     * bookmark row before any report row; both requests must succeed.
     */
    @Test
    fun `concurrent actioned resolutions of sibling reports do not deadlock`() {
        val executor = Executors.newFixedThreadPool(2)
        try {
            repeat(8) {
                val bookmarkId = createBookmark("alice")
                val reports = listOf(reportId("bob", bookmarkId), reportId("carol", bookmarkId))
                val barrier = CyclicBarrier(2)
                val statuses = reports.map { id ->
                    executor.submit(
                        Callable {
                            barrier.await()
                            mockMvc.perform(
                                put("/api/v1/admin/reports/{id}", id).with(moderator())
                                    .contentType(MediaType.APPLICATION_JSON).content("""{"resolution":"actioned"}"""),
                            ).andReturn().response.status
                        },
                    )
                }.map { it.get() }

                assertThat(statuses).containsExactly(200, 200)
                reports.forEach { id ->
                    val report = reportRepository.findById(UUID.fromString(id)).orElseThrow()
                    assertThat(report.status).isEqualTo(ReportStatus.ACTIONED)
                }
                mockMvc.perform(get("/api/v1/bookmarks/{id}", bookmarkId).with(user("alice")))
                    .andExpect(jsonPath("$.status").value("hidden"))
            }
        } finally {
            executor.shutdownNow()
        }
    }

    @Test
    fun `decisions can be revised and re-opened`() {
        val bookmarkId = createBookmark("alice")
        val id = reportId("bob", bookmarkId)

        resolve(id, "dismissed")

        // dismissed → actioned applies the actioning side effects
        mockMvc.perform(
            put("/api/v1/admin/reports/{id}", id).with(moderator())
                .contentType(MediaType.APPLICATION_JSON).content("""{"resolution":"actioned","note":"on review"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("actioned"))
            .andExpect(jsonPath("$.resolutionNote").value("on review"))
        mockMvc.perform(get("/api/v1/bookmarks/{id}", bookmarkId).with(user("alice")))
            .andExpect(jsonPath("$.status").value("hidden"))

        // re-opening clears the resolution fields and the report is editable again
        mockMvc.perform(
            put("/api/v1/admin/reports/{id}", id).with(moderator())
                .contentType(MediaType.APPLICATION_JSON).content("""{"resolution":"open"}"""),
        )
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.status").value("open"))
            .andExpect(jsonPath("$.resolvedBy").doesNotExist())
            .andExpect(jsonPath("$.resolvedAt").doesNotExist())
            .andExpect(jsonPath("$.resolutionNote").doesNotExist())
        mockMvc.perform(
            put("/api/v1/reports/{id}", id).with(user("bob"))
                .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"other"}"""),
        ).andExpect(status().isOk)

        // re-opening lands in the audit trail under its own action
        mockMvc.perform(get("/api/v1/admin/audit-log").param("action", "report.reopened").with(admin()))
            .andExpect(status().isOk)
            .andExpect(jsonPath("$.items[0].targetId").value(id))
    }

    /**
     * Regression: report A dismissed → report B filed (open) → re-opening A would
     * leave two open reports for the same (bookmark, reporter), violating
     * uq_reports_one_open_per_reporter. That must be a graceful 409, not the
     * unhandled integrity violation that surfaced as a 500.
     */
    @Test
    fun `re-opening a report is a 409 when another open report already exists for the pair`() {
        val bookmarkId = createBookmark("alice")
        val first = reportId("bob", bookmarkId)
        resolve(first, "dismissed")
        // now that A is resolved the reporter may file B; B becomes the open one
        val second = reportId("bob", bookmarkId)

        mockMvc.perform(
            put("/api/v1/admin/reports/{id}", first).with(moderator())
                .contentType(MediaType.APPLICATION_JSON).content("""{"resolution":"open"}"""),
        ).andExpect(status().isConflict)

        // no partial state: A stays dismissed, B stays the single open report
        assertThat(reportRepository.findById(UUID.fromString(first)).orElseThrow().status)
            .isEqualTo(ReportStatus.DISMISSED)
        assertThat(reportRepository.findById(UUID.fromString(second)).orElseThrow().status)
            .isEqualTo(ReportStatus.OPEN)
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
            .andExpect(jsonPath("$.errors[0].message").value("Resolution must be one of: open, dismissed, actioned."))

        mockMvc.perform(
            put("/api/v1/admin/reports/{id}", reportId).with(moderator())
                .header("Accept-Language", "pl")
                .contentType(MediaType.APPLICATION_JSON).content("""{"resolution":"ignore"}"""),
        )
            .andExpect(status().isBadRequest)
            .andExpect(jsonPath("$.errors[0].message").value("Rozstrzygnięcie musi być jednym z: open, dismissed, actioned."))
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
