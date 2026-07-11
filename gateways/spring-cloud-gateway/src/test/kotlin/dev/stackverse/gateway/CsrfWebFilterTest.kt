package dev.stackverse.gateway

import dev.stackverse.gateway.config.GatewayProperties
import dev.stackverse.gateway.config.SecurityConfig
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

        filter().filter(
            exchange,
            WebFilterChain {
                continued = true
                it.response.setComplete()
            },
        ).block()

        assertTrue(continued)
        val cookie = exchange.response.cookies.getFirst(Csrf.COOKIE_NAME)
        assertNotNull(cookie)
        assertEquals("/", cookie!!.path)
        assertEquals("Lax", cookie.sameSite)
        assertFalse(cookie.isHttpOnly)
        assertFalse(cookie.isSecure)
        assertTrue(
            Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}").matches(cookie.value),
            cookie.value,
        )
    }

    @Test
    fun `https public url issues a secure xsrf cookie`() {
        val exchange = exchange(MockServerHttpRequest.get("/auth/session"))

        filter(publicUrl = "https://stackverse.example").filter(
            exchange,
            WebFilterChain { it.response.setComplete() },
        ).block()

        val cookie = exchange.response.cookies.getFirst(Csrf.COOKIE_NAME)
        assertNotNull(cookie)
        assertTrue(cookie!!.isSecure)
        assertFalse(cookie.isHttpOnly)
        assertEquals("Lax", cookie.sameSite)
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

        filter().filter(
            exchange,
            WebFilterChain {
                continued = true
                it.response.setComplete()
            },
        ).block()

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
            filter().filter(
                exchange,
                WebFilterChain {
                    continued = true
                    it.response.setComplete()
                },
            ).block()

            assertFalse(continued)
            assertForbidden(exchange, "Missing or mismatched X-XSRF-TOKEN header.")
        }
    }

    @Test
    fun `every contracted state changing method protects the exact api route`() {
        for (method in listOf(HttpMethod.POST, HttpMethod.PUT, HttpMethod.PATCH, HttpMethod.DELETE)) {
            val exchange = stateChangingApiExchange(
                method = method,
                path = "/api",
                cookie = "cookie-token",
                header = null,
            )
            var continued = false

            filter().filter(
                exchange,
                WebFilterChain {
                    continued = true
                    it.response.setComplete()
                },
            ).block()

            assertFalse(continued, method.name())
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

            filter().filter(
                exchange,
                WebFilterChain {
                    continued = true
                    it.response.setComplete()
                },
            ).block()

            assertFalse(continued, "headers=${headers.toList()}")
            assertForbidden(exchange, "Cross-origin state-changing requests are not supported.")
        }
    }

    @Test
    fun `safe and non-api requests do not require csrf tokens`() {
        for ((method, path) in listOf(HttpMethod.GET to "/api/v1/bookmarks", HttpMethod.POST to "/apiary")) {
            val exchange = exchange(MockServerHttpRequest.method(method, URI.create(path)))
            var continued = false

            filter().filter(
                exchange,
                WebFilterChain {
                    continued = true
                    it.response.setComplete()
                },
            ).block()

            assertTrue(continued, "$method $path")
            assertNull(exchange.response.statusCode, "$method $path")
        }
    }

    @Test
    fun `default ports and ipv6 hosts use canonical public origins`() {
        val cases = listOf(
            "http://localhost:80" to "http://localhost",
            "https://stackverse.example:443" to "https://stackverse.example",
            "https://[::1]:443" to "https://[::1]",
        )

        for ((publicUrl, origin) in cases) {
            val exchange = stateChangingApiExchange(
                cookie = "token-123",
                header = "token-123",
                "Origin" to origin,
            )
            var continued = false

            filter(publicUrl).filter(
                exchange,
                WebFilterChain {
                    continued = true
                    it.response.setComplete()
                },
            ).block()

            assertTrue(continued, "$publicUrl -> $origin")
            assertNull(exchange.response.statusCode)
        }
    }

    private fun stateChangingApiExchange(
        cookie: String?,
        header: String?,
        vararg headers: Pair<String, String>,
    ): MockServerWebExchange =
        stateChangingApiExchange(
            method = HttpMethod.POST,
            path = "/api/v1/bookmarks",
            cookie = cookie,
            header = header,
            headers = headers.asList(),
        )

    private fun stateChangingApiExchange(
        method: HttpMethod,
        path: String,
        cookie: String?,
        header: String?,
        headers: List<Pair<String, String>> = emptyList(),
    ): MockServerWebExchange {
        val request = MockServerHttpRequest.method(method, URI.create(path))
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

    private fun filter(publicUrl: String = "http://localhost:8000"): CsrfWebFilter {
        val gateway = gateway(publicUrl)
        return CsrfWebFilter(gateway, SecurityConfig().csrfTokenRepository(gateway))
    }

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
