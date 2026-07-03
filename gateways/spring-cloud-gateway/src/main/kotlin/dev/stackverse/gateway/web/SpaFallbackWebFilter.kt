package dev.stackverse.gateway.web

import dev.stackverse.gateway.config.GatewayProperties
import org.springframework.core.Ordered
import org.springframework.http.HttpMethod
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono

/**
 * SPA deep links when the gateway serves fallback static files (no `FRONTEND_URL`):
 * a GET for a client-side route like `/bookmarks/123` — anything that is not
 * `/api`, not `/auth`, and not a file — rewrites to `/index.html`, so the router
 * in the SPA takes over. The equivalent of yarp's `MapFallbackToFile`. Inert when
 * a frontend static server or dev server is proxied instead — the catch-all route
 * owns page delivery then.
 */
@Component
class SpaFallbackWebFilter(gateway: GatewayProperties) : WebFilter, Ordered {

    private val active = gateway.frontendUrl == null

    /** Late — after security and CSRF; only rewrites what nothing else handles. */
    override fun getOrder(): Int = 0

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        if (!active || !isSpaRoute(exchange)) {
            return chain.filter(exchange)
        }
        return chain.filter(exchange.mutate().request { it.path("/index.html") }.build())
    }

    private fun isSpaRoute(exchange: ServerWebExchange): Boolean {
        if (exchange.request.method !in setOf(HttpMethod.GET, HttpMethod.HEAD)) {
            return false
        }
        val path = exchange.request.path.value()
        return !isUnder(path, "/api") &&
            !isUnder(path, "/auth") &&
            !path.substringAfterLast('/').contains('.') // a file (has an extension) is served as-is
    }

    /** Segment-aware: /api and /api/x are the gateway's, /apiary is the SPA's. */
    private fun isUnder(path: String, prefix: String): Boolean =
        path == prefix || path.startsWith("$prefix/")
}
