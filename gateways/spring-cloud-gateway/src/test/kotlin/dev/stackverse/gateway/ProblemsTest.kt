package dev.stackverse.gateway

import dev.stackverse.gateway.common.Problems
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange

class ProblemsTest {

    @Test
    fun `problem writer emits rfc9457 problem json with escaped gateway-authored fields`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/api/v1/bookmarks").build())

        Problems.write(exchange, HttpStatus.FORBIDDEN, "For\"bidden", "Bad \\ token").block()

        assertEquals(HttpStatus.FORBIDDEN, exchange.response.statusCode)
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, exchange.response.headers.contentType)
        assertEquals(
            """{"type":"about:blank","title":"For\"bidden","status":403,"detail":"Bad \\ token"}""",
            exchange.response.bodyAsString.block(),
        )
    }
}
