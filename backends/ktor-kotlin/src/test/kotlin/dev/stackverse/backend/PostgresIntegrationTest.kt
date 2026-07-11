package dev.stackverse.backend

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.module.kotlin.readValue
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpServer
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.cookie
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.server.testing.ApplicationTestBuilder
import io.ktor.server.testing.testApplication
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.slf4j.LoggerFactory
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date
import java.util.UUID
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@Testcontainers
class PostgresIntegrationTest {
    private val mapper = jsonMapper()

    @BeforeEach
    fun resetDatabase() {
        newDataSource().use { dataSource ->
            Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            dataSource.connection.use { connection ->
                connection.execute("truncate audit_entries, reports, bookmark_tags, bookmarks, messages, user_accounts cascade")
                seedMessage(connection, "error.account.blocked", "en", "This account is blocked.")
                seedMessage(connection, "validation.url.required", "en", "URL is required.")
                seedMessage(connection, "validation.title.required", "en", "Title is required.")
                seedMessage(connection, "validation.visibility.invalid", "en", "Visibility is invalid.")
                seedMessage(connection, "validation.report.reason.invalid", "en", "Report reason is invalid.")
            }
        }
        logEvents.list.clear()
    }

    @Test
    fun `bearer authentication provisions identity while roles sessions and failures stay at the HTTP boundary`() = withApplication {
        val me = client.get("/api/v1/me") {
            bearer("demo", "moderator", extraRoles = listOf("offline_access"))
        }
        assertEquals(HttpStatusCode.OK, me.status)
        val identity = me.json()
        assertEquals("demo", identity["username"].asText())
        assertEquals("Demo User", identity["name"].asText())
        assertEquals("demo@example.com", identity["email"].asText())
        assertEquals(listOf("moderator"), identity["roles"].map(JsonNode::asText))
        assertEquals(1, rowCount("select count(*) from user_accounts where username = 'demo'"))

        val cookieOnly = client.get("/api/v1/me") {
            cookie("stackverse_session", "opaque-session-value")
        }
        assertProblem(cookieOnly, HttpStatusCode.Unauthorized, "Authentication is required.")

        val moderatorOnAdmin = client.get("/api/v1/admin/users") {
            bearer("demo", "moderator")
        }
        assertProblem(moderatorOnAdmin, HttpStatusCode.Forbidden, "You do not have the role required for this operation.")
        assertTrue(events("authz_denied").any { it.keyValue("actor") == "demo" })

        val invalidTokens = listOf(
            token("wrong-audience", audience = listOf("another-api")),
            token("expired", expiresAt = Instant.now().minusSeconds(60)),
            token("future", notBefore = Instant.now().plusSeconds(300)),
            "not-a-jwt-secret-sentinel",
        )
        invalidTokens.forEach { raw ->
            val response = client.get("/api/v1/me") {
                header(HttpHeaders.Authorization, "Bearer $raw")
            }
            assertProblem(response, HttpStatusCode.Unauthorized, "Missing or invalid bearer token.")
        }
        assertEquals(invalidTokens.size, events("jwt_validation_failed").size)
        assertFalse(logPayload().contains("not-a-jwt-secret-sentinel"))
        assertFalse(logPayload().contains("opaque-session-value"))
    }

    @Test
    fun `bookmark routes preserve ownership masking validation filters and stable keyset pagination`() = withApplication {
        val invalid = client.post("/api/v1/bookmarks") {
            bearer("alice")
            jsonBody("""{"url":"","title":"","visibility":"friends"}""")
        }
        assertEquals(HttpStatusCode.BadRequest, invalid.status)
        assertProblemContentType(invalid)
        val invalidBody = invalid.json()
        assertEquals(
            setOf("validation.url.required", "validation.title.required", "validation.visibility.invalid"),
            invalidBody["errors"].map { it["messageKey"].asText() }.toSet(),
        )
        assertEquals("URL is required.", invalidBody["errors"].first { it["field"].asText() == "url" }["message"].asText())

        val created = createBookmark(
            client,
            owner = "alice",
            title = "Ktor 100% Guide",
            notes = "Literal underscore_name and backslash \\",
            tags = listOf(" Ktor ", "kotlin", "ktor"),
        )
        assertEquals("private", created.visibility)
        assertEquals(listOf("kotlin", "ktor"), created.tags)

        val masked = client.get("/api/v1/bookmarks/${created.id}") { bearer("bob") }
        assertProblem(masked, HttpStatusCode.NotFound)

        val anonymousDefault = client.get("/api/v1/bookmarks")
        assertProblem(anonymousDefault, HttpStatusCode.Unauthorized, "Authentication is required.")
        assertEquals(DEPRECATION, anonymousDefault.headers["Deprecation"])

        val published = client.put("/api/v1/bookmarks/${created.id}") {
            bearer("alice")
            jsonBody(
                mapper.writeValueAsString(
                    mapOf(
                        "url" to "https://example.com/ktor",
                        "title" to "Ktor 100% Guide",
                        "notes" to "Literal underscore_name and backslash \\",
                        "tags" to listOf("ktor", "kotlin"),
                        "visibility" to "public",
                    ),
                ),
            )
        }
        assertEquals(HttpStatusCode.OK, published.status)
        assertEquals("public", published.json()["visibility"].asText())
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/bookmarks/${created.id}").status)

        createBookmark(client, "alice", "Ktor secondary", tags = listOf("ktor"), visibility = "public")
        val tagAnd = client.get("/api/v1/bookmarks?visibility=public&tag=ktor&tag=kotlin&q=100%25")
        assertEquals(HttpStatusCode.OK, tagAnd.status)
        assertEquals(listOf(created.id.toString()), tagAnd.json()["items"].map { it["id"].asText() })

        val beforePagination = (1..4).map { index ->
            createBookmark(client, "alice", "Page $index", tags = listOf("paging"), visibility = "public")
        }
        val firstPage = client.get("/api/v2/bookmarks?visibility=public&tag=paging&size=2")
        assertEquals(HttpStatusCode.OK, firstPage.status)
        val firstBody = firstPage.json()
        val firstIds = firstBody["items"].map { it["id"].asText() }
        val cursor = firstBody["nextCursor"].asText()
        assertEquals(2, firstIds.size)
        createBookmark(client, "alice", "Concurrent insert", tags = listOf("paging"), visibility = "public")
        val secondPage = client.get("/api/v2/bookmarks?visibility=public&tag=paging&size=2&cursor=$cursor")
        val secondIds = secondPage.json()["items"].map { it["id"].asText() }
        assertEquals(2, secondIds.size)
        assertTrue(firstIds.toSet().intersect(secondIds.toSet()).isEmpty())
        assertEquals(beforePagination.map { it.id.toString() }.toSet(), (firstIds + secondIds).toSet())

        val tags = client.get("/api/v1/tags") { bearer("alice") }.json()["tags"]
        assertTrue(tags.any { it["tag"].asText() == "paging" && it["count"].asInt() == 5 })

        assertProblem(client.delete("/api/v1/bookmarks/${created.id}") { bearer("bob") }, HttpStatusCode.NotFound)
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/bookmarks/${created.id}") { bearer("alice") }.status)
        assertProblem(client.get("/api/v1/bookmarks/${created.id}"), HttpStatusCode.NotFound)
    }

    @Test
    fun `message administration localizes bundles revalidates etags and keeps text out of logs`() = withApplication {
        val before = client.get("/api/v1/messages")
        assertEquals(HttpStatusCode.OK, before.status)
        val beforeEtag = assertNotNull(before.headers[HttpHeaders.ETag])
        assertEquals("no-cache", before.headers[HttpHeaders.CacheControl])

        val denied = client.post("/api/v1/messages") {
            bearer("regular")
            jsonBody(messageJson("ui.greeting", "en", "private-log-sentinel"))
        }
        assertProblem(denied, HttpStatusCode.Forbidden)

        val english = createMessage(client, "ui.greeting", "en", "private-log-sentinel")
        createMessage(client, "ui.greeting", "pl", "Cześć")
        createMessage(client, "ui.only-en", "en", "English fallback")

        val duplicate = client.post("/api/v1/messages") {
            admin()
            jsonBody(messageJson("ui.greeting", "en", "Duplicate"))
        }
        assertProblem(duplicate, HttpStatusCode.Conflict)

        val afterCreate = client.get("/api/v1/messages?key=ui.greeting&language=en")
        assertNotEquals(beforeEtag, afterCreate.headers[HttpHeaders.ETag])
        assertEquals(1, afterCreate.json()["totalItems"].asInt())

        val polishBundle = client.get("/api/v1/messages/bundle") {
            header(HttpHeaders.AcceptLanguage, "de;q=1.0, pl;q=0.9, en;q=0.5")
        }
        assertEquals("pl", polishBundle.headers[HttpHeaders.ContentLanguage])
        assertEquals("Cześć", polishBundle.json()["messages"]["ui.greeting"].asText())
        assertEquals("English fallback", polishBundle.json()["messages"]["ui.only-en"].asText())

        val explicitEnglish = client.get("/api/v1/messages/bundle?lang=en") {
            header(HttpHeaders.AcceptLanguage, "pl")
        }
        assertEquals("en", explicitEnglish.headers[HttpHeaders.ContentLanguage])
        assertEquals("private-log-sentinel", explicitEnglish.json()["messages"]["ui.greeting"].asText())

        val bundleEtag = assertNotNull(polishBundle.headers[HttpHeaders.ETag])
        val notModified = client.get("/api/v1/messages/bundle") {
            header(HttpHeaders.AcceptLanguage, "pl")
            header(HttpHeaders.IfNoneMatch, "\"unrelated\", $bundleEtag")
        }
        assertEquals(HttpStatusCode.NotModified, notModified.status)
        assertEquals("", notModified.bodyAsText())

        val updated = client.put("/api/v1/messages/${english.id}") {
            admin()
            jsonBody(messageJson("ui.greeting", "en", "updated-private-log-sentinel"))
        }
        assertEquals(HttpStatusCode.OK, updated.status)
        val changedBundle = client.get("/api/v1/messages/bundle?lang=en")
        assertNotEquals(explicitEnglish.headers[HttpHeaders.ETag], changedBundle.headers[HttpHeaders.ETag])
        assertEquals("updated-private-log-sentinel", changedBundle.json()["messages"]["ui.greeting"].asText())

        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/messages/${english.id}") { admin() }.status)
        val audit = client.get("/api/v1/admin/audit-log?action=message.deleted") { admin() }.json()
        assertEquals(1, audit["totalItems"].asInt())
        assertEquals("message.deleted", audit["items"][0]["action"].asText())

        assertTrue(events("message_created").isNotEmpty())
        assertTrue(events("message_updated").isNotEmpty())
        assertTrue(events("message_deleted").isNotEmpty())
        assertFalse(logPayload().contains("private-log-sentinel"))
        assertFalse(logPayload().contains("updated-private-log-sentinel"))
    }

    @Test
    fun `moderation auto-resolves sibling reports requires explicit restore and blocks accounts on the next request`() = withApplication {
        val bookmark = createBookmark(client, "owner", "Reported", tags = listOf("review"), visibility = "public")
        val firstReport = createReport(client, "reporter-one", bookmark.id, "spam", "sensitive-report-comment")
        val secondReport = createReport(client, "reporter-two", bookmark.id, "offensive", "second private comment")

        val duplicate = client.post("/api/v1/bookmarks/${bookmark.id}/reports") {
            bearer("reporter-one")
            jsonBody("""{"reason":"other"}""")
        }
        assertProblem(duplicate, HttpStatusCode.Conflict)

        val updated = client.put("/api/v1/reports/${firstReport.id}") {
            bearer("reporter-one")
            jsonBody("""{"reason":"broken-link","comment":"replacement private comment"}""")
        }
        assertEquals("broken-link", updated.json()["reason"].asText())
        assertProblem(
            client.put("/api/v1/reports/${firstReport.id}") {
                bearer("reporter-two")
                jsonBody("""{"reason":"spam"}""")
            },
            HttpStatusCode.NotFound,
        )

        val resolved = client.put("/api/v1/admin/reports/${firstReport.id}") {
            moderator()
            jsonBody("""{"resolution":"actioned","note":"moderation private note"}""")
        }
        assertEquals(HttpStatusCode.OK, resolved.status)
        assertEquals("actioned", resolved.json()["status"].asText())
        val sibling = client.get("/api/v1/reports?status=actioned") { bearer("reporter-two") }.json()["items"].single()
        assertEquals(secondReport.id.toString(), sibling["id"].asText())
        assertEquals("moderator", sibling["resolvedBy"].asText())

        assertProblem(client.get("/api/v1/bookmarks/${bookmark.id}"), HttpStatusCode.NotFound)
        assertEquals("hidden", client.get("/api/v1/bookmarks/${bookmark.id}") { bearer("owner") }.json()["status"].asText())
        assertProblem(
            client.put("/api/v1/bookmarks/${bookmark.id}") {
                bearer("owner")
                jsonBody("""{"url":"https://example.com/reported","title":"Republish","visibility":"public"}""")
            },
            HttpStatusCode.Conflict,
        )

        val reopened = client.put("/api/v1/admin/reports/${firstReport.id}") {
            moderator()
            jsonBody("""{"resolution":"open","note":"ignored"}""")
        }
        assertEquals("open", reopened.json()["status"].asText())
        assertFalse(reopened.json().has("resolvedBy"))
        assertProblem(client.get("/api/v1/bookmarks/${bookmark.id}"), HttpStatusCode.NotFound)

        val restored = client.put("/api/v1/admin/bookmarks/${bookmark.id}/status") {
            moderator()
            jsonBody("""{"status":"active"}""")
        }
        assertEquals("active", restored.json()["status"].asText())
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/bookmarks/${bookmark.id}").status)

        val blocked = client.put("/api/v1/admin/users/reporter-one/status") {
            admin()
            jsonBody("""{"status":"blocked","reason":"account-private-reason"}""")
        }
        assertEquals("blocked", blocked.json()["status"].asText())
        assertProblem(client.get("/api/v1/me") { bearer("reporter-one") }, HttpStatusCode.Forbidden, "This account is blocked.")
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/bookmarks?visibility=public").status)

        val selfBlock = client.put("/api/v1/admin/users/admin/status") {
            admin()
            jsonBody("""{"status":"blocked","reason":"self"}""")
        }
        assertProblem(selfBlock, HttpStatusCode.Conflict, "Admins cannot block themselves.")
        assertEquals(
            "active",
            client.put("/api/v1/admin/users/reporter-one/status") {
                admin()
                jsonBody("""{"status":"active"}""")
            }.json()["status"].asText(),
        )
        assertEquals(HttpStatusCode.OK, client.get("/api/v1/me") { bearer("reporter-one") }.status)

        val statsDateBefore = LocalDate.now(ZoneOffset.UTC)
        val stats = client.get("/api/v1/admin/stats") { moderator() }
        val statsDateAfter = LocalDate.now(ZoneOffset.UTC)
        assertEquals(HttpStatusCode.OK, stats.status)
        val statsBody = stats.json()
        assertEquals(30, statsBody["daily"].size())
        val firstStatsDate = LocalDate.parse(statsBody["daily"].first()["date"].asText())
        val lastStatsDate = LocalDate.parse(statsBody["daily"].last()["date"].asText())
        assertEquals(lastStatsDate.minusDays(29), firstStatsDate)
        assertTrue(lastStatsDate in setOf(statsDateBefore, statsDateAfter))
        assertTrue(statsBody["totals"]["users"].asInt() >= 5)
        assertTrue(statsBody["topTags"].any { it["tag"].asText() == "review" })
        val statsEtag = assertNotNull(stats.headers[HttpHeaders.ETag])
        assertEquals(
            HttpStatusCode.NotModified,
            client.get("/api/v1/admin/stats") {
                moderator()
                header(HttpHeaders.IfNoneMatch, statsEtag)
            }.status,
        )
        assertProblem(client.get("/api/v1/admin/stats") { bearer("regular") }, HttpStatusCode.Forbidden)

        val audit = client.get("/api/v1/admin/audit-log?size=100") { admin() }.json()["items"]
        assertTrue(audit.any { it["action"].asText() == "report.resolved" })
        assertTrue(audit.any { it["action"].asText() == "report.reopened" })
        assertTrue(audit.any { it["action"].asText() == "bookmark.status-changed" })
        assertTrue(events("blocked_user_rejected").any { it.keyValue("actor") == "reporter-one" })
        assertFalse(logPayload().contains("sensitive-report-comment"))
        assertFalse(logPayload().contains("replacement private comment"))
        assertFalse(logPayload().contains("moderation private note"))
        assertFalse(logPayload().contains("account-private-reason"))
    }

    @Test
    fun `reporter withdrawal permits replacement while resolved reports and reopen conflicts stay protected`() = withApplication {
        val bookmark = createBookmark(client, "owner", "Report lifecycle", visibility = "public")
        val withdrawn = createReport(client, "reporter", bookmark.id, "spam")
        assertEquals(HttpStatusCode.NoContent, client.delete("/api/v1/reports/${withdrawn.id}") { bearer("reporter") }.status)

        val dismissed = createReport(client, "reporter", bookmark.id, "other")
        assertEquals(
            "dismissed",
            client.put("/api/v1/admin/reports/${dismissed.id}") {
                moderator()
                jsonBody("""{"resolution":"dismissed"}""")
            }.json()["status"].asText(),
        )
        assertProblem(
            client.put("/api/v1/reports/${dismissed.id}") {
                bearer("reporter")
                jsonBody("""{"reason":"spam"}""")
            },
            HttpStatusCode.Conflict,
        )
        assertProblem(client.delete("/api/v1/reports/${dismissed.id}") { bearer("reporter") }, HttpStatusCode.Conflict)

        val replacement = createReport(client, "reporter", bookmark.id, "broken-link")
        val reopenConflict = client.put("/api/v1/admin/reports/${dismissed.id}") {
            moderator()
            jsonBody("""{"resolution":"open"}""")
        }
        assertProblem(reopenConflict, HttpStatusCode.Conflict, "The reporter already has another open report on this bookmark.")
        assertEquals(
            replacement.id.toString(),
            client.get("/api/v1/reports?status=open") { bearer("reporter") }.json()["items"].single()["id"].asText(),
        )

        val privateBookmark = createBookmark(client, "owner", "Private report target")
        assertProblem(
            client.post("/api/v1/bookmarks/${privateBookmark.id}/reports") {
                bearer("reporter")
                jsonBody("""{"reason":"spam"}""")
            },
            HttpStatusCode.NotFound,
        )
    }

    @Test
    fun `database transactions commit rollback and readiness reflect the PostgreSQL boundary`() {
        val dataSource = newDataSource()
        val database = Database(dataSource)
        try {
            assertTrue(runBlocking { database.ready() })
            assertTrue(runBlocking { database.read { autoCommit } })
            runBlocking {
                database.transaction {
                    insertAccount("committed")
                }
            }
            assertEquals(1, rowCount("select count(*) from user_accounts where username = 'committed'"))

            assertFailsWith<IllegalStateException> {
                runBlocking {
                    database.transaction {
                        insertAccount("rolled-back")
                        error("force rollback")
                    }
                }
            }
            assertEquals(0, rowCount("select count(*) from user_accounts where username = 'rolled-back'"))
        } finally {
            dataSource.close()
        }
        assertFalse(runBlocking { database.ready() })
    }

    @Test
    fun `message seed initialization is idempotent and preserves runtime edits`(@TempDir seedDirectory: Path) {
        val seedFile = seedDirectory.resolve("en.json")
        Files.writeString(seedFile, """{"ui.seeded":"Seed value"}""")
        val dataSource = newDataSource()
        try {
            val database = Database(dataSource)
            val audit = AuditRepository(database, mapper)
            val messages = MessageRepository(database, audit, mapper, logger)
            val initializer = StackverseInitializer(config(seedDirectory), mapper, database, messages, logger)
            initializer.initialize()
            assertEquals("Seed value", runBlocking { messages.text("ui.seeded", "en") })

            runBlocking {
                database.transaction {
                    execute("update messages set text = ? where key = ? and language = ?", "Runtime edit", "ui.seeded", "en")
                }
            }
            Files.writeString(seedFile, """{"ui.seeded":"Changed seed","ui.new":"New value"}""")
            initializer.initialize()
            assertEquals("Runtime edit", runBlocking { messages.text("ui.seeded", "en") })
            assertEquals("New value", runBlocking { messages.text("ui.new", "en") })

            val missing = StackverseInitializer(config(seedDirectory.resolve("missing")), mapper, database, messages, logger)
            val failure = assertFailsWith<IllegalStateException> { missing.initialize() }
            assertContains(failure.message.orEmpty(), "Message seed directory not found")
        } finally {
            dataSource.close()
        }
    }

    private fun withApplication(test: suspend ApplicationTestBuilder.() -> Unit) = testApplication {
        val dataSource = newDataSource()
        application {
            configureStackverseDependencies(config(), mapper, logger) { dataSource }
            stackverseModule()
        }
        try {
            test()
        } finally {
            dataSource.close()
        }
    }

    private fun newDataSource() =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = postgres.jdbcUrl
                username = postgres.username
                password = postgres.password
                maximumPoolSize = 4
                minimumIdle = 0
                initializationFailTimeout = 5_000
                poolName = "stackverse-ktor-test-${UUID.randomUUID()}"
            },
        )

    private fun config(seedDirectory: Path? = null) = Config(
        port = 8080,
        dbHost = postgres.host,
        dbPort = postgres.getMappedPort(5432).toString(),
        dbName = postgres.databaseName,
        dbUser = postgres.username,
        dbPassword = postgres.password,
        issuerUri = ISSUER,
        jwksUri = jwksUri,
        seedMessagesDir = seedDirectory?.toString() ?: "../../spec/messages",
        logLevel = "info",
        logFormat = "json",
    )

    private fun seedMessage(connection: Connection, key: String, language: String, text: String) {
        val now = nowUtc()
        connection.execute(
            """
            insert into messages (id, key, language, text, description, created_at, updated_at)
            values (?, ?, ?, ?, null, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(), key, language, text, now, now,
        )
    }

    private fun Connection.insertAccount(username: String) {
        val now = nowUtc()
        execute(
            "insert into user_accounts (username, first_seen, last_seen, status) values (?, ?, ?, 'ACTIVE')",
            username,
            now,
            now,
        )
    }

    private fun rowCount(sql: String): Long =
        newDataSource().use { dataSource ->
            dataSource.connection.use { connection -> connection.queryLong(sql) }
        }

    private suspend fun createBookmark(
        client: HttpClient,
        owner: String,
        title: String,
        notes: String? = null,
        tags: List<String> = emptyList(),
        visibility: String? = null,
    ): BookmarkResponse {
        val body = mapper.writeValueAsString(
            mapOf(
                "url" to "https://example.com/${UUID.randomUUID()}",
                "title" to title,
                "notes" to notes,
                "tags" to tags,
                "visibility" to visibility,
            ),
        )
        val response = client.post("/api/v1/bookmarks") {
            bearer(owner)
            jsonBody(body)
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val bookmark = mapper.readValue<BookmarkResponse>(response.bodyAsText())
        assertEquals("/api/v1/bookmarks/${bookmark.id}", response.headers[HttpHeaders.Location])
        return bookmark
    }

    private suspend fun createReport(
        client: HttpClient,
        reporter: String,
        bookmarkId: UUID,
        reason: String,
        comment: String? = null,
    ): ReportResponse {
        val response = client.post("/api/v1/bookmarks/$bookmarkId/reports") {
            bearer(reporter)
            jsonBody(mapper.writeValueAsString(mapOf("reason" to reason, "comment" to comment)))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        return mapper.readValue(response.bodyAsText())
    }

    private suspend fun createMessage(client: HttpClient, key: String, language: String, text: String): MessageResponse {
        val response = client.post("/api/v1/messages") {
            admin()
            jsonBody(messageJson(key, language, text))
        }
        assertEquals(HttpStatusCode.Created, response.status, response.bodyAsText())
        val message = mapper.readValue<MessageResponse>(response.bodyAsText())
        assertEquals("/api/v1/messages/${message.id}", response.headers[HttpHeaders.Location])
        return message
    }

    private fun messageJson(key: String, language: String, text: String) =
        mapper.writeValueAsString(mapOf("key" to key, "language" to language, "text" to text, "description" to "private description"))

    private fun HttpRequestBuilder.bearer(username: String, vararg roles: String, extraRoles: List<String> = emptyList()) {
        header(HttpHeaders.Authorization, "Bearer ${token(username, roles.toList() + extraRoles)}")
    }

    private fun HttpRequestBuilder.moderator() = bearer("moderator", "moderator")

    private fun HttpRequestBuilder.admin() = bearer("admin", "moderator", "admin")

    private fun HttpRequestBuilder.jsonBody(body: String) {
        contentType(ContentType.Application.Json)
        setBody(body)
    }

    private suspend fun HttpResponse.json(): JsonNode = mapper.readTree(bodyAsText())

    private suspend fun assertProblem(response: HttpResponse, status: HttpStatusCode, detail: String? = null) {
        assertEquals(status, response.status, response.bodyAsText())
        assertProblemContentType(response)
        val problem = response.json()
        assertEquals(status.value, problem["status"].asInt())
        if (detail != null) assertEquals(detail, problem["detail"]?.asText())
    }

    private fun assertProblemContentType(response: HttpResponse) {
        val contentType = assertNotNull(response.contentType())
        assertEquals("application", contentType.contentType)
        assertEquals("problem+json", contentType.contentSubtype)
    }

    private fun events(name: String): List<ILoggingEvent> =
        logEvents.list.filter { it.keyValue("event") == name }

    private fun ILoggingEvent.keyValue(key: String): Any? = keyValuePairs?.firstOrNull { it.key == key }?.value

    private fun logPayload(): String = buildString {
        logEvents.list.forEach { event ->
            append(event.formattedMessage)
            event.keyValuePairs.orEmpty().forEach { pair -> append('|').append(pair.key).append('=').append(pair.value) }
            appendLine()
        }
    }

    companion object {
        private const val ISSUER = "https://issuer.stackverse.test/realms/stackverse"
        private val signingKey: RSAKey = RSAKeyGenerator(2048).keyID("stackverse-test-key").generate()
        private lateinit var jwksServer: HttpServer
        private lateinit var jwksUri: String
        private val logger = LoggerFactory.getLogger("dev.stackverse.backend.PostgresIntegrationTest")
        private val logEvents = ListAppender<ILoggingEvent>()

        @Container
        @JvmStatic
        val postgres = PostgreSQLContainer("postgres:18.4-alpine")

        @BeforeAll
        @JvmStatic
        fun startJwksServer() {
            jwksServer = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
            jwksServer.createContext("/jwks") { exchange ->
                val bytes = "{\"keys\":[${signingKey.toPublicJWK().toJSONString()}]}".toByteArray(StandardCharsets.UTF_8)
                exchange.responseHeaders.add(HttpHeaders.ContentType, ContentType.Application.Json.toString())
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
            jwksServer.start()
            jwksUri = "http://${jwksServer.address.hostString}:${jwksServer.address.port}/jwks"
            (logger as ch.qos.logback.classic.Logger).addAppender(logEvents)
            logEvents.start()
        }

        @AfterAll
        @JvmStatic
        fun stopJwksServer() {
            jwksServer.stop(0)
            logEvents.stop()
            (logger as ch.qos.logback.classic.Logger).detachAppender(logEvents)
        }

        private fun token(
            username: String,
            roles: List<String> = emptyList(),
            issuer: String = ISSUER,
            audience: List<String> = listOf(AUDIENCE),
            expiresAt: Instant = Instant.now().plusSeconds(600),
            notBefore: Instant? = null,
        ): String {
            val claims = JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .expirationTime(Date.from(expiresAt))
                .issueTime(Date())
                .claim("preferred_username", username)
                .claim("name", "${username.replaceFirstChar { it.uppercase() }} User")
                .claim("email", "$username@example.com")
                .claim("realm_access", mapOf("roles" to roles))
                .apply { if (notBefore != null) notBeforeTime(Date.from(notBefore)) }
                .build()
            return SignedJWT(
                JWSHeader.Builder(JWSAlgorithm.RS256).keyID(signingKey.keyID).build(),
                claims,
            ).apply { sign(RSASSASigner(signingKey)) }.serialize()
        }
    }
}
