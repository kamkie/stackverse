package dev.stackverse.backend

import dev.stackverse.backend.audit.AuditRepository
import dev.stackverse.backend.bookmark.BookmarkRepository
import dev.stackverse.backend.moderation.ReportRepository
import org.hamcrest.Matchers.greaterThanOrEqualTo
import org.hamcrest.Matchers.hasSize
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.header
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath
import org.springframework.test.web.servlet.result.MockMvcResultMatchers.status
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.time.ZoneOffset

class StatsAndAuditApiTest : IntegrationTest() {

    @Autowired
    lateinit var bookmarkRepository: BookmarkRepository

    @Autowired
    lateinit var reportRepository: ReportRepository

    @Autowired
    lateinit var auditRepository: AuditRepository

    @Autowired
    lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun clean() {
        reportRepository.deleteAll()
        bookmarkRepository.deleteAll()
    }

    @Test
    fun `stats aggregate totals, a zero-filled 30-day series, and top tags`() {
        mockMvc.perform(
            post("/api/v1/bookmarks").with(user("stats-user")).contentType(MediaType.APPLICATION_JSON)
                .content("""{"url":"https://example.com","title":"counted","visibility":"public","tags":["stats-tag"]}"""),
        ).andExpect(status().isCreated)

        val today = LocalDate.now(ZoneOffset.UTC).toString()
        val body = mockMvc.perform(get("/api/v1/admin/stats").with(moderator()))
            .andExpect(status().isOk)
            .andExpect(header().exists("ETag"))
            .andExpect(header().string("Cache-Control", "no-cache"))
            .andExpect(jsonPath("$.totals.bookmarks").value(1))
            .andExpect(jsonPath("$.totals.publicBookmarks").value(1))
            .andExpect(jsonPath("$.totals.hiddenBookmarks").value(0))
            .andExpect(jsonPath("$.totals.openReports").value(0))
            .andExpect(jsonPath("$.totals.users").value(greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.daily", hasSize<Any>(30)))
            .andExpect(jsonPath("$.daily[29].date").value(today))
            .andExpect(jsonPath("$.daily[29].bookmarksCreated").value(1))
            .andExpect(jsonPath("$.daily[29].activeUsers").value(greaterThanOrEqualTo(1)))
            .andExpect(jsonPath("$.daily[0].bookmarksCreated").value(0))
            .andReturn()

        // zero-filled: all 30 consecutive days present, oldest first
        val daily = objectMapper.readTree(body.response.contentAsString).get("daily")
        val dates = daily.toList().map { it.get("date").asString() }
        assert(dates.first() == LocalDate.now(ZoneOffset.UTC).minusDays(29).toString())
        assert(dates == dates.sorted()) { "daily series must be oldest first" }

        val etag = body.response.getHeader("ETag")!!
        mockMvc.perform(get("/api/v1/admin/stats").with(moderator()).header("If-None-Match", etag))
            .andExpect(status().isNotModified)

        mockMvc.perform(get("/api/v1/admin/stats").with(user("someone"))).andExpect(status().isForbidden)
        mockMvc.perform(get("/api/v1/admin/stats")).andExpect(status().isUnauthorized)
    }

    @Test
    fun `every backoffice mutation leaves an audit entry, browsable by admins`() {
        auditRepository.deleteAll()

        // one mutation of each audited kind
        val bookmarkId = objectMapper.readTree(
            mockMvc.perform(
                post("/api/v1/bookmarks").with(user("audited")).contentType(MediaType.APPLICATION_JSON)
                    .content("""{"url":"https://example.com","title":"t","visibility":"public"}"""),
            ).andReturn().response.contentAsString,
        ).get("id").asString()
        val reportId = objectMapper.readTree(
            mockMvc.perform(
                post("/api/v1/bookmarks/{id}/reports", bookmarkId).with(user("reporter"))
                    .contentType(MediaType.APPLICATION_JSON).content("""{"reason":"spam"}"""),
            ).andReturn().response.contentAsString,
        ).get("id").asString()
        mockMvc.perform(
            put("/api/v1/admin/reports/{id}", reportId).with(moderator())
                .contentType(MediaType.APPLICATION_JSON).content("""{"resolution":"actioned","note":"n"}"""),
        ).andExpect(status().isOk)
        mockMvc.perform(
            put("/api/v1/admin/users/{u}/status", "reporter").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"blocked","reason":"r"}"""),
        ).andExpect(status().isOk)
        mockMvc.perform(
            post("/api/v1/messages").with(admin()).contentType(MediaType.APPLICATION_JSON)
                .content("""{"key":"test.audit.entry","language":"en","text":"x"}"""),
        ).andExpect(status().isCreated)

        mockMvc.perform(get("/api/v1/admin/audit-log").with(admin()))
            .andExpect(status().isOk)
            // report.resolved + bookmark.status-changed + user.blocked + message.created
            .andExpect(jsonPath("$.totalItems").value(4))
            // newest first
            .andExpect(jsonPath("$.items[0].action").value("message.created"))

        mockMvc.perform(get("/api/v1/admin/audit-log").param("action", "report.resolved").with(admin()))
            .andExpect(jsonPath("$.totalItems").value(1))
            .andExpect(jsonPath("$.items[0].actor").value("moderator"))
            .andExpect(jsonPath("$.items[0].targetId").value(reportId))
            .andExpect(jsonPath("$.items[0].detail.resolution").value("actioned"))

        mockMvc.perform(get("/api/v1/admin/audit-log").param("actor", "admin").with(admin()))
            .andExpect(jsonPath("$.totalItems").value(2))

        // admin-only
        mockMvc.perform(get("/api/v1/admin/audit-log").with(moderator())).andExpect(status().isForbidden)

        // unblock so other tests aren't affected
        mockMvc.perform(
            put("/api/v1/admin/users/{u}/status", "reporter").with(admin())
                .contentType(MediaType.APPLICATION_JSON).content("""{"status":"active"}"""),
        ).andExpect(status().isOk)
    }

    @Test
    fun `health endpoints respond`() {
        mockMvc.perform(get("/healthz")).andExpect(status().isOk)
        mockMvc.perform(get("/readyz")).andExpect(status().isOk)
    }
}
