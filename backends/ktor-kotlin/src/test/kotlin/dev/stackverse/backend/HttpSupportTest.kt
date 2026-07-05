package dev.stackverse.backend

import io.ktor.client.HttpClient
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.testing.testApplication
import kotlin.test.Test
import kotlin.test.assertEquals

class HttpSupportTest {
    @Test
    fun `page and size helpers use defaults and enforce contract bounds`() = testApplication {
        application { installProbeRoutes() }

        client.assertOk("/paging", "0:20")
        client.assertOk("/paging?page=2&size=100", "2:100")
        client.assertValidation("/paging?page=-1", "page:validation.page.invalid")
        client.assertValidation("/paging?page=not-a-number", "page:validation.page.invalid")
        client.assertValidation("/paging?size=0", "size:validation.size.invalid")
        client.assertValidation("/paging?size=101", "size:validation.size.invalid")
    }

    @Test
    fun `bookmark query helper normalizes filters and reports field errors`() = testApplication {
        application { installProbeRoutes() }

        client.assertOk(
            "/bookmark-query?tag=%20Kotlin%20&tag=ktor&q=Guide&visibility=public",
            "kotlin,ktor|Guide|public",
        )
        client.assertValidation("/bookmark-query?tag=bad_tag", "tag:validation.tag.invalid")
        client.assertValidation("/bookmark-query?visibility=friends", "visibility:validation.visibility.invalid")
        client.assertValidation("/bookmark-query?q=${"x".repeat(201)}", "q:validation.q.too-long")
    }

    @Test
    fun `report status helper accepts known states and rejects unknown states`() = testApplication {
        application { installProbeRoutes() }

        client.assertOk("/report-status", "none")
        client.assertOk("/report-status?status=actioned", "actioned")
        client.assertValidation("/report-status?status=resolved", "status:validation.report.status.invalid")
    }

    @Test
    fun `UUID path helper rejects malformed UUIDs as bad requests`() = testApplication {
        application { installProbeRoutes() }

        client.assertOk("/uuid/11111111-1111-1111-1111-111111111111", "11111111-1111-1111-1111-111111111111")
        client.assertApiProblem("/uuid/not-a-uuid", HttpStatusCode.BadRequest, "Invalid UUID.")
    }

    private fun Application.installProbeRoutes() {
        routing {
            get("/paging") {
                call.respondValidation {
                    "${call.pageParam()}:${call.sizeParam()}"
                }
            }
            get("/bookmark-query") {
                call.respondValidation {
                    val query = call.bookmarkQuery()
                    "${query.tags.joinToString(",")}|${query.q.orEmpty()}|${query.visibility.orEmpty()}"
                }
            }
            get("/report-status") {
                call.respondValidation {
                    call.optionalReportStatus() ?: "none"
                }
            }
            get("/uuid/{id}") {
                call.respondApiProblem {
                    call.uuidPath("id").toString()
                }
            }
        }
    }

    private suspend fun ApplicationCall.respondValidation(block: () -> String) {
        try {
            respondText(block())
        } catch (problem: ValidationProblem) {
            respondText(
                problem.violations.joinToString(";") { "${it.field}:${it.messageKey}" },
                status = HttpStatusCode.BadRequest,
            )
        }
    }

    private suspend fun ApplicationCall.respondApiProblem(block: () -> String) {
        try {
            respondText(block())
        } catch (problem: ApiProblem) {
            respondText(problem.detail.orEmpty(), status = problem.status)
        }
    }

    private suspend fun HttpClient.assertOk(path: String, expectedBody: String) {
        val response = get(path)
        assertEquals(HttpStatusCode.OK, response.status)
        assertEquals(expectedBody, response.bodyAsText())
    }

    private suspend fun HttpClient.assertValidation(path: String, expectedBody: String) {
        val response = get(path)
        assertEquals(HttpStatusCode.BadRequest, response.status)
        assertEquals(expectedBody, response.bodyAsText())
    }

    private suspend fun HttpClient.assertApiProblem(path: String, expectedStatus: HttpStatusCode, expectedBody: String) {
        val response = get(path)
        assertEquals(expectedStatus, response.status)
        assertEquals(expectedBody, response.bodyAsText())
    }
}
