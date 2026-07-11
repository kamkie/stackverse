package dev.stackverse.backend

import com.fasterxml.jackson.module.kotlin.readValue
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.testing.testApplication
import org.slf4j.LoggerFactory
import kotlin.test.Test
import kotlin.test.assertEquals

class ApplicationTest {
    @Test
    fun `health endpoint is served by the real stack module`() = testApplication {
        val mapper = jsonMapper()
        application {
            configureStackverseDependencies(testConfig(), mapper, testLogger(), ::testDataSource)
            stackverseModule()
        }

        val response = client.get("/healthz")

        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals("ok", response.bodyAsText())
    }

    @Test
    fun `authenticated route returns a problem when no token is present`() = testApplication {
        val mapper = jsonMapper()
        application {
            configureStackverseDependencies(testConfig(), mapper, testLogger(), ::testDataSource)
            stackverseModule()
        }

        val response = client.get("/api/v1/me")
        val problem = mapper.readValue<Problem>(response.bodyAsText())

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals("Unauthorized", problem.title)
        assertEquals(401, problem.status)
        assertEquals("Authentication is required.", problem.detail)
    }

    @Test
    fun `deprecated bookmark list headers are added before auth rejection`() = testApplication {
        val mapper = jsonMapper()
        application {
            configureStackverseDependencies(testConfig(), mapper, testLogger(), ::testDataSource)
            stackverseModule()
        }

        val response = client.get("/api/v1/bookmarks") {
            header(HttpHeaders.Authorization, "Bearer not-a-jwt")
        }

        assertEquals(HttpStatusCode.Unauthorized, response.status)
        assertEquals(DEPRECATION, response.headers["Deprecation"])
        assertEquals(SUNSET, response.headers["Sunset"])
        assertEquals(SUCCESSOR_LINK, response.headers[HttpHeaders.Link])
    }

    private fun testConfig() = Config(
        port = 8080,
        dbHost = "localhost",
        dbPort = "1",
        dbName = "stackverse",
        dbUser = "stackverse",
        dbPassword = "stackverse",
        issuerUri = "http://localhost:8180/realms/stackverse",
        jwksUri = "",
        seedMessagesDir = "../../spec/messages",
        logLevel = "info",
        logFormat = "json",
    )

    private fun testLogger() = LoggerFactory.getLogger(ApplicationTest::class.java)

    private fun testDataSource() =
        HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = "jdbc:postgresql://localhost:1/stackverse"
                username = "stackverse"
                password = "stackverse"
                maximumPoolSize = 1
                minimumIdle = 0
                initializationFailTimeout = -1L
            },
        )
}
