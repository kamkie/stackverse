package dev.stackverse.gateway

import dev.stackverse.gateway.config.GatewayProperties
import dev.stackverse.gateway.web.Csrf
import dev.stackverse.gateway.web.CsrfWebFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.http.HttpCookie
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import java.net.URI

class CsrfWebFilterTest {

    @Test
    fun `safe requests issue readable xsrf cookie and continue`() {
        val exchange = exchange(MockServerHttpRequest.get("/auth/session"))
        var continued = false

        filter().filter(exchange, WebFilterChain {
            continued = true
            it.response.setComplete()
        }).block()

        assertTrue(continued)
        val cookie = exchange.response.cookies.getFirst(Csrf.COOKIE_NAME)
        assertNotNull(cookie)
        assertEquals("/", cookie!!.path)
        assertEquals("Lax", cookie.sameSite)
        assertFalse(cookie.isHttpOnly)
        assertFalse(cookie.isSecure)
        assertTrue(Regex("[0-9A-F]{32}").matches(cookie.value), cookie.value)
    }

    @Test
    fun `existing xsrf cookie is not overwritten`() {
        val exchange = exchange(
            MockServerHttpRequest.get("/api/v1/messages/bundle")
                .cookie(HttpCookie(Csrf.COOKIE_NAME, "already-issued")),
        )

        filter().filter(exchange, WebFilterChain { it.response.setComplete() }).block()

        assertNull(exchange.response.cookies.getFirst(Csrf.COOKIE_NAME))
    }

    @Test
    fun `state changing api request passes with matching csrf token and same-origin signals`() {
        val exchange = stateChangingApiExchange(
            cookie = "token-123",
            header = "token-123",
            "Origin" to "http://localhost:8000",
            "Sec-Fetch-Site" to "same-origin",
        )
        var continued = false

        filter().filter(exchange, WebFilterChain {
            continued = true
            it.response.setComplete()
        }).block()

        assertTrue(continued)
        assertNull(exchange.response.statusCode)
    }

    @Test
    fun `state changing api request rejects missing or mismatched csrf token`() {
        val cases = listOf(
            stateChangingApiExchange(cookie = null, header = null),
            stateChangingApiExchange(cookie = "cookie-token", header = null),
            stateChangingApiExchange(cookie = "cookie-token", header = "header-token"),
        )

        for (exchange in cases) {
            var continued = false
            filter().filter(exchange, WebFilterChain {
                continued = true
                it.response.setComplete()
            }).block()

            assertFalse(continued)
            assertForbidden(exchange, "Missing or mismatched X-XSRF-TOKEN header.")
        }
    }

    @Test
    fun `state changing api request rejects non-canonical browser origin signals`() {
        val cases = listOf(
            arrayOf("Origin" to "http://localhost:8000/"),
            arrayOf("Origin" to "http://localhost"),
            arrayOf("Origin" to "https://localhost:8000"),
            arrayOf("Origin" to "http://evil.example:8000"),
            arrayOf("Sec-Fetch-Site" to "same-site"),
            arrayOf("Sec-Fetch-Site" to "cross-site"),
            arrayOf("Sec-Fetch-Site" to "navigate"),
        )

        for (headers in cases) {
            val exchange = stateChangingApiExchange(
                cookie = "token-123",
                header = "token-123",
                *headers,
            )
            var continued = false

            filter().filter(exchange, WebFilterChain {
                continued = true
                it.response.setComplete()
            }).block()

            assertFalse(continued, "headers=${headers.toList()}")
            assertForbidden(exchange, "Cross-origin state-changing requests are not supported.")
        }
    }

    @Test
    fun `safe and non-api requests do not require csrf tokens`() {
        for ((method, path) in listOf(HttpMethod.GET to "/api/v1/bookmarks", HttpMethod.POST to "/apiary")) {
            val exchange = exchange(MockServerHttpRequest.method(method, URI.create(path)))
            var continued = false

            filter().filter(exchange, WebFilterChain {
                continued = true
                it.response.setComplete()
            }).block()

            assertTrue(continued, "$method $path")
            assertNull(exchange.response.statusCode, "$method $path")
        }
    }

    private fun stateChangingApiExchange(
        cookie: String?,
        header: String?,
        vararg headers: Pair<String, String>,
    ): MockServerWebExchange {
        val request = MockServerHttpRequest.post("/api/v1/bookmarks")
        if (cookie != null) {
            request.cookie(HttpCookie(Csrf.COOKIE_NAME, cookie))
        }
        if (header != null) {
            request.header(Csrf.HEADER_NAME, header)
        }
        headers.forEach { (name, value) -> request.header(name, value) }
        return exchange(request)
    }

    private fun assertForbidden(exchange: MockServerWebExchange, detail: String) {
        assertEquals(HttpStatus.FORBIDDEN, exchange.response.statusCode)
        assertEquals(MediaType.APPLICATION_PROBLEM_JSON, exchange.response.headers.contentType)
        assertTrue(exchange.response.bodyAsString.block()!!.contains(detail))
    }

    private fun exchange(request: MockServerHttpRequest.BaseBuilder<*>): MockServerWebExchange =
        MockServerWebExchange.from(request.build())

    private fun filter(publicUrl: String = "http://localhost:8000") = CsrfWebFilter(gateway(publicUrl))

    private fun gateway(publicUrl: String) = GatewayProperties(
        backendUrl = URI("http://localhost:8080"),
        frontendUrl = null,
        publicUrl = URI(publicUrl),
        oidc = GatewayProperties.Oidc(
            issuerUri = "http://localhost:8180/realms/stackverse",
            internalIssuerUri = null,
            clientId = "stackverse-gateway",
            clientSecret = "stackverse-secret",
        ),
    )
}
