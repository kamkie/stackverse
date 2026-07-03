package dev.stackverse.gateway.config

import dev.stackverse.gateway.relay.TokenRelayFilter
import dev.stackverse.gateway.web.Csrf
import org.springframework.cloud.gateway.route.RouteLocator
import org.springframework.cloud.gateway.route.builder.RouteLocatorBuilder
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpHeaders

// The proxy half of the gateway contract (docs/ARCHITECTURE.md): /api/** to the
// backend with token relay, /** to the frontend SPA upstream when FRONTEND_URL
// is set. Without it, requests fall through to static resources (SPA_ROOT or
// the bundled placeholder) with an index.html fallback — see SpaFallbackWebFilter.
// The literal /auth endpoints outrank both: Spring Security's filters handle
// login and callback before any routing, and annotated controllers are mapped
// ahead of gateway routes.
// (Line comments on purpose: Kotlin block comments nest, so a /** glob inside
// KDoc is a syntax error.)
@Configuration
class RouteConfig {

    @Bean
    fun routes(
        builder: RouteLocatorBuilder,
        gateway: GatewayProperties,
        tokenRelay: TokenRelayFilter,
    ): RouteLocator {
        val routes = builder.routes()
        routes.route("api") { route ->
            route.path("/api/**")
                .filters { filters ->
                    // The browser's cookies (session key, CSRF token) are gateway-only
                    // state; nothing upstream may see them — the session lives at the edge.
                    filters.removeRequestHeader(HttpHeaders.COOKIE)
                    // Validated at the gateway; not part of the API semantics.
                    filters.removeRequestHeader(Csrf.HEADER_NAME)
                    // The gateway session is the only source of upstream identity — a
                    // client-supplied Authorization header must never reach the backend.
                    filters.removeRequestHeader(HttpHeaders.AUTHORIZATION)
                    filters.filter(tokenRelay)
                }
                .uri(gateway.backendUrl)
        }
        gateway.frontendUrl?.let { frontendUrl ->
            routes.route("frontend") { route ->
                route.order(100) // behind /api and the literal /auth endpoints
                    .path("/**")
                    .filters { filters -> filters.removeRequestHeader(HttpHeaders.COOKIE) }
                    .uri(frontendUrl)
            }
        }
        return routes.build()
    }
}
