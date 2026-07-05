package dev.stackverse.gateway

import dev.stackverse.gateway.config.GatewayProperties
import dev.stackverse.gateway.web.SpaFallbackWebFilter
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.http.HttpMethod
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.web.server.WebFilterChain
import java.net.URI

class SpaFallbackWebFilterTest {

    @Test
    fun `fallback rewrites spa document routes when no frontend upstream is configured`() {
        for (path in listOf("/", "/admin/users", "/bookmarks/123", "/apiary")) {
            assertEquals("/index.html", pathAfterFilter(path), path)
        }
    }

    @Test
    fun `fallback leaves gateway routes assets and non-document methods unchanged`() {
        val cases = listOf(
            HttpMethod.GET to "/api",
            HttpMethod.GET to "/api/v1/bookmarks",
            HttpMethod.GET to "/auth/session",
            HttpMethod.GET to "/assets/app.js",
            HttpMethod.GET to "/favicon.ico",
            HttpMethod.POST to "/admin/users",
        )

        for ((method, path) in cases) {
            assertEquals(path, pathAfterFilter(path, method), "$method $path")
        }
    }

    @Test
    fun `fallback is inactive when a frontend upstream is configured`() {
        assertEquals(
            "/admin/users",
            pathAfterFilter("/admin/users", frontendUrl = "http://localhost:5173"),
        )
    }

    private fun pathAfterFilter(
        path: String,
        method: HttpMethod = HttpMethod.GET,
        frontendUrl: String? = null,
    ): String {
        val filter = SpaFallbackWebFilter(gateway(frontendUrl))
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.method(method, URI.create(path)).build())
        var seenPath: String? = null

        filter.filter(exchange, WebFilterChain {
            seenPath = it.request.path.value()
            it.response.setComplete()
        }).block()

        return checkNotNull(seenPath)
    }

    private fun gateway(frontendUrl: String?) = GatewayProperties(
        backendUrl = URI("http://localhost:8080"),
        frontendUrl = frontendUrl,
        publicUrl = URI("http://localhost:8000"),
        oidc = GatewayProperties.Oidc(
            issuerUri = "http://localhost:8180/realms/stackverse",
            internalIssuerUri = null,
            clientId = "stackverse-gateway",
            clientSecret = "stackverse-secret",
        ),
    )
}
