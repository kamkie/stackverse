package dev.stackverse.gateway.web

import dev.stackverse.gateway.config.GatewayProperties
import org.springframework.core.Ordered
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/** Contracted browser hardening headers for gateway-owned responses. */
@Component
class SecurityHeadersWebFilter(private val gateway: GatewayProperties) : WebFilter, Ordered {

    /**
     * Before Spring Security's WebFilterChainProxy (order -100), because the OIDC
     * challenge and callback filters may complete /auth responses themselves.
     */
    override fun getOrder(): Int = -160

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        exchange.response.beforeCommit {
            apply(exchange)
            Mono.empty()
        }
        return chain.filter(exchange)
    }

    private fun apply(exchange: ServerWebExchange) {
        val headers = exchange.response.headers
        val apiResponse = isApi(exchange)

        headers.set("X-Content-Type-Options", "nosniff")
        if (gateway.cookiesSecure) {
            headers.set("Strict-Transport-Security", "max-age=31536000; includeSubDomains")
        }

        if (apiResponse) {
            return
        }

        headers.set("Referrer-Policy", "same-origin")
        headers.set(
            "Content-Security-Policy",
            "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'",
        )
        headers.set("X-Frame-Options", "DENY")
        headers.set("Cross-Origin-Opener-Policy", "same-origin")
        headers.set("Cross-Origin-Resource-Policy", "same-origin")
    }

    private fun isApi(exchange: ServerWebExchange): Boolean {
        val path = exchange.request.path.value()
        return path == "/api" || path.startsWith("/api/")
    }
}
