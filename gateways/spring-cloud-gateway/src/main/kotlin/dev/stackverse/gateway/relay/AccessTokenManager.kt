package dev.stackverse.gateway.relay

import dev.stackverse.gateway.common.logEvent
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.ClientResponse
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebSession
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant
import java.util.concurrent.TimeoutException

/**
 * Hands out the session's access token for the /api relay, refreshing it against the
 * IdP token endpoint when it is about to expire and persisting the refreshed tokens
 * back into the Redis-backed session.
 *
 * This is a deliberate hand-rolled refresh path rather than Spring's
 * `ReactiveOAuth2AuthorizedClientManager`: the whole exchange is one form POST, this
 * repo optimizes for self-contained code a reader can follow end to end, and the
 * contract's two refresh-failure modes (rejected vs unavailable — see below) need
 * exact, distinct handling. Concurrent requests may occasionally refresh twice;
 * Keycloak permits refresh-token reuse by default, so the loser of the race simply
 * stores an equally valid token.
 */
@Component
class AccessTokenManager(
    private val authorizedClients: ServerOAuth2AuthorizedClientRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val webClient = WebClient.create()

    /**
     * Emits a valid access token for the authenticated session, or completes empty
     * when the session can no longer produce one (refresh token expired or revoked) —
     * the session is destroyed and the caller relays anonymously. Errors with
     * [IdpUnavailableException] when the IdP cannot be asked, or answers with its own
     * failure instead of a verdict on the grant — either outcome says nothing about
     * the session, so it survives (docs/ARCHITECTURE.md).
     */
    fun accessToken(auth: OAuth2AuthenticationToken, exchange: ServerWebExchange): Mono<String> =
        authorizedClients
            .loadAuthorizedClient<OAuth2AuthorizedClient>(auth.authorizedClientRegistrationId, auth, exchange)
            // materialize absence: a plain switchIfEmpty would also fire when a
            // *rejected* refresh completes empty, destroying the session twice
            .singleOptional()
            .flatMap { maybeClient ->
                // an authenticated session without stored tokens cannot produce one
                val client = maybeClient.orElse(null) ?: return@flatMap destroyAndDegrade(auth, exchange)
                val expiresAt = client.accessToken.expiresAt
                if (expiresAt == null || expiresAt.minus(EXPIRY_SKEW).isAfter(Instant.now())) {
                    Mono.just(client.accessToken.tokenValue)
                } else {
                    refresh(auth, client, exchange)
                }
            }

    private fun refresh(
        auth: OAuth2AuthenticationToken,
        client: OAuth2AuthorizedClient,
        exchange: ServerWebExchange,
    ): Mono<String> {
        val refreshToken = client.refreshToken?.tokenValue
            ?: return destroyAndDegrade(auth, exchange)
        val registration = client.clientRegistration
        val start = System.nanoTime()
        fun elapsedMs() = Duration.ofNanos(System.nanoTime() - start).toMillis()

        return webClient.post()
            .uri(registration.providerDetails.tokenUri)
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(
                BodyInserters.fromFormData("grant_type", "refresh_token")
                    .with("refresh_token", refreshToken)
                    .with("client_id", registration.clientId)
                    .with("client_secret", registration.clientSecret),
            )
            .exchangeToMono { response -> classify(response.statusCode().value(), response, elapsedMs()) }
            .timeout(IDP_TIMEOUT)
            // Anything that is not a verdict on the grant — unreachable, timeout,
            // garbage in a 2xx body — proves nothing about the session: log the
            // dependency failure and error out, letting the /api relay answer 503
            // while the session (whose refresh token may still be valid) survives.
            .onErrorResume { e ->
                when (e) {
                    is IdpUnavailableException -> Mono.error(e) // already logged at the throw site
                    else -> {
                        log.logEvent(
                            Level.ERROR, "dependency_call_failed",
                            if (e is TimeoutException) "timeout" else "failure",
                            "Keycloak was unreachable during token refresh; the session is kept",
                            "dependency" to "keycloak",
                            "duration_ms" to elapsedMs(),
                            "error_code" to e.javaClass.simpleName,
                            cause = e,
                        )
                        Mono.error(IdpUnavailableException("The IdP could not be reached to refresh the access token", e))
                    }
                }
            }
            .flatMap { outcome ->
                when (outcome) {
                    is RefreshOutcome.Rejected -> {
                        // degraded but self-healing (the session is destroyed below) — WARN per docs/LOGGING.md §5
                        log.logEvent(
                            Level.WARN, "token_refresh_failed", "failure",
                            "Token refresh rejected by the IdP (${outcome.status}); treating the session as expired",
                            "error_code" to "idp_rejected",
                            "idp_status" to outcome.status,
                        )
                        destroyAndDegrade(auth, exchange)
                    }

                    is RefreshOutcome.Refreshed ->
                        save(auth, client, outcome, refreshToken, exchange).thenReturn(outcome.accessToken)
                }
            }
    }

    /** Sorts the token endpoint's answer into the contract's three buckets. */
    private fun classify(status: Int, response: ClientResponse, durationMs: Long): Mono<RefreshOutcome> =
        when {
            status in 200..299 ->
                response.bodyToMono(object : ParameterizedTypeReference<Map<String, Any>>() {})
                    .map<RefreshOutcome> { body ->
                        RefreshOutcome.Refreshed(
                            accessToken = body["access_token"] as? String
                                ?: error("token response carries no access_token"),
                            refreshToken = body["refresh_token"] as? String,
                            expiresInSeconds = (body["expires_in"] as? Number)?.toLong() ?: 300L,
                        )
                    }
                    // a bodyless 2xx is garbage, not a verdict on the grant — without
                    // this it would complete empty and the relay would silently
                    // degrade to anonymous while the session lives on
                    .switchIfEmpty(Mono.error { IllegalStateException("the IdP answered $status with an empty body") })

            // Only an authoritative rejection of the grant proves the session is dead —
            // RFC 6749 §5.2: a 400 (invalid_grant, expired/revoked refresh token) or a
            // 401 (client authentication).
            status == 400 || status == 401 ->
                response.releaseBody().thenReturn(RefreshOutcome.Rejected(status))

            // A 5xx or 429 is the IdP failing at its own job and says nothing about the session.
            else -> {
                log.logEvent(
                    Level.ERROR, "dependency_call_failed", "failure",
                    "Keycloak answered $status during token refresh; the session is kept",
                    "dependency" to "keycloak",
                    "duration_ms" to durationMs,
                    "error_code" to "idp_status_$status",
                )
                response.releaseBody()
                    .then(Mono.error(IdpUnavailableException("The IdP answered $status to the token refresh")))
            }
        }

    /** Persist the rotated tokens into the session so every gateway instance sees them. */
    private fun save(
        auth: OAuth2AuthenticationToken,
        client: OAuth2AuthorizedClient,
        refreshed: RefreshOutcome.Refreshed,
        previousRefreshToken: String,
        exchange: ServerWebExchange,
    ): Mono<Void> {
        val now = Instant.now()
        val updated = OAuth2AuthorizedClient(
            client.clientRegistration,
            client.principalName,
            OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                refreshed.accessToken,
                now,
                now.plusSeconds(refreshed.expiresInSeconds),
            ),
            OAuth2RefreshToken(refreshed.refreshToken ?: previousRefreshToken, now),
        )
        return authorizedClients.saveAuthorizedClient(updated, auth, exchange)
    }

    /**
     * The session can no longer produce a token: destroy it and degrade the request
     * to anonymous. The SPA notices via the backend's 401 or /auth/session.
     */
    private fun destroyAndDegrade(auth: OAuth2AuthenticationToken, exchange: ServerWebExchange): Mono<String> =
        exchange.session
            .flatMap(WebSession::invalidate)
            .doOnSuccess {
                log.logEvent(
                    Level.INFO, "session_destroyed", "success",
                    "Session destroyed after a failed token refresh; request degraded to anonymous",
                    "reason" to "token_refresh_failed",
                    "actor" to auth.name,
                )
            }
            .then(Mono.empty())

    private sealed interface RefreshOutcome {
        data class Refreshed(val accessToken: String, val refreshToken: String?, val expiresInSeconds: Long) : RefreshOutcome
        data class Rejected(val status: Int) : RefreshOutcome
    }

    companion object {
        /** Refresh slightly early so a token cannot expire mid-flight to the backend. */
        private val EXPIRY_SKEW: Duration = Duration.ofSeconds(30)

        /** A hanging IdP is an outage, not a reason to hang the user's request. */
        private val IDP_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

/**
 * A token refresh failed because the IdP was unreachable, failing (5xx/429), or
 * unintelligible — a transient dependency outage, distinct from the IdP rejecting
 * the refresh token itself.
 */
class IdpUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
