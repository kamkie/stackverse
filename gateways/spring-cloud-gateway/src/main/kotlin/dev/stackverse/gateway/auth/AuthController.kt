package dev.stackverse.gateway.auth

import dev.stackverse.gateway.common.logEvent
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.http.HttpStatus
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebSession
import reactor.core.publisher.Mono
import java.util.Optional

/**
 * The session-facing half of the `/auth` surface (docs/ARCHITECTURE.md); login and
 * callback are Spring Security's OIDC filters, configured in SecurityConfig.
 */
@RestController
class AuthController(
    private val authorizedClients: ServerOAuth2AuthorizedClientRepository,
    private val idpLogout: RpInitiatedLogout,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    /** Who is logged in, for the SPA — never a 401, never a redirect. */
    @GetMapping("/auth/session")
    fun session(exchange: ServerWebExchange): Mono<Map<String, Any>> =
        exchange.getPrincipal<OAuth2AuthenticationToken>()
            .map<Map<String, Any>> { auth -> mapOf("authenticated" to true, "username" to auth.name) }
            .defaultIfEmpty(mapOf("authenticated" to false))

    /**
     * Logout semantics: local-first, IdP best-effort. The local session (Redis entry +
     * cookie — the only credential the browser holds) is destroyed *first* because it
     * is the only death the gateway can guarantee; the RP-initiated logout at Keycloak
     * follows, detached from the request so a client that disconnects mid-logout
     * cannot cancel the revocation. Anonymous logout is a no-op 204.
     */
    @PostMapping("/auth/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun logout(exchange: ServerWebExchange): Mono<Void> =
        exchange.getPrincipal<OAuth2AuthenticationToken>()
            .flatMap { auth ->
                authorizedClients
                    .loadAuthorizedClient<OAuth2AuthorizedClient>(auth.authorizedClientRegistrationId, auth, exchange)
                    .map { client -> Optional.ofNullable(client.refreshToken?.tokenValue) }
                    .defaultIfEmpty(Optional.empty())
                    .flatMap { refreshToken ->
                        exchange.session
                            .flatMap(WebSession::invalidate)
                            .doOnSuccess {
                                log.logEvent(
                                    Level.INFO, "session_destroyed", "success",
                                    "Session destroyed by user logout",
                                    "reason" to "logout",
                                    "actor" to auth.name,
                                )
                            }
                            .then(Mono.defer { refreshToken.map(::detachedIdpLogout).orElse(Mono.empty()) })
                    }
            }

    /**
     * Runs the IdP revocation on its own subscription: `toFuture()` subscribes
     * immediately, so cancelling the (already answered or aborted) request does not
     * abandon the call — the user's intent is recorded. [RpInitiatedLogout.logout]
     * never errors; failures are logged there.
     */
    private fun detachedIdpLogout(refreshToken: String): Mono<Void> {
        val revocation = idpLogout.logout(refreshToken).toFuture()
        return Mono.create { sink -> revocation.whenComplete { _, _ -> sink.success() } }
    }
}
