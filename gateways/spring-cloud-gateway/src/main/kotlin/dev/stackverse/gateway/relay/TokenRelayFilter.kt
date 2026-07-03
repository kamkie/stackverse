package dev.stackverse.gateway.relay

import dev.stackverse.gateway.common.Problems
import org.springframework.cloud.gateway.filter.GatewayFilter
import org.springframework.cloud.gateway.filter.GatewayFilterChain
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/**
 * The /api guard, applied on the `api` route after the browser's headers are
 * stripped: attaches a fresh Bearer token when a session exists, relays anonymously
 * when none does — the spec's public surface works logged-out, and which endpoints
 * require auth is the backend's decision, not the gateway's.
 */
@Component
class TokenRelayFilter(private val tokenManager: AccessTokenManager) : GatewayFilter {

    override fun filter(exchange: ServerWebExchange, chain: GatewayFilterChain): Mono<Void> =
        exchange.getPrincipal<OAuth2AuthenticationToken>()
            .flatMap { auth -> tokenManager.accessToken(auth, exchange) }
            .map { accessToken ->
                exchange.mutate()
                    .request { request -> request.headers { it.setBearerAuth(accessToken) } }
                    .build()
            }
            .defaultIfEmpty(exchange)
            .flatMap(chain::filter)
            .onErrorResume(IdpUnavailableException::class.java) {
                // Transient IdP outage (already logged as dependency_call_failed): the
                // refresh token may still be valid, so the session stays. Failing the
                // request explicitly beats relaying it anonymously — the user did not
                // log out — and beats an unhandled 500 (docs/ARCHITECTURE.md).
                Problems.write(
                    exchange, HttpStatus.SERVICE_UNAVAILABLE,
                    "Service Unavailable", "Authentication is temporarily unavailable; please retry.",
                )
            }
}
