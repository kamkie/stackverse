package dev.stackverse.gateway

import dev.stackverse.gateway.config.GatewayProperties
import dev.stackverse.gateway.web.SecurityHeadersWebFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import java.net.URI

class SecurityHeadersWebFilterTest {

    @Test
    fun `hsts is emitted only when public url is https`() {
        val httpHeaders = headersFor("/", "http://localhost:8000")
        assertNull(httpHeaders.getFirst("Strict-Transport-Security"))

        val httpsHeaders = headersFor("/", "https://stackverse.example")
        assertEquals("max-age=31536000; includeSubDomains", httpsHeaders.getFirst("Strict-Transport-Security"))
    }

    @Test
    fun `https api responses get hsts without document-only headers`() {
        val headers = headersFor("/api/v1/messages/bundle", "https://stackverse.example")

        assertEquals("nosniff", headers.getFirst("X-Content-Type-Options"))
        assertEquals("max-age=31536000; includeSubDomains", headers.getFirst("Strict-Transport-Security"))
        assertNull(headers.getFirst("Content-Security-Policy"))
        assertNull(headers.getFirst("X-Frame-Options"))
        assertNull(headers.getFirst("Cross-Origin-Opener-Policy"))
        assertNull(headers.getFirst("Cross-Origin-Resource-Policy"))
        assertNull(headers.getFirst("Referrer-Policy"))
    }

    private fun headersFor(path: String, publicUrl: String): org.springframework.http.HttpHeaders {
        val filter = SecurityHeadersWebFilter(gateway(publicUrl))
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get(path).build())
        filter.filter(exchange, WebFilterChain { it.response.setComplete() }).block()
        return exchange.response.headers
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
