package dev.stackverse.gateway.relay

import dev.stackverse.gateway.common.logEvent
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.security.oauth2.core.OAuth2ErrorCodes
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClientResponseException
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
 * Spring Security owns the refresh grant exchange, response decoding, and rotated-token
 * persistence through [ReactiveOAuth2AuthorizedClientManager]. This adapter keeps only
 * the Stackverse-specific policy around the manager: an authoritative refresh-token
 * rejection destroys the session and relays anonymously, while IdP unavailability keeps
 * the session and lets the route answer 503. Concurrent requests may occasionally
 * refresh twice; Keycloak permits refresh-token reuse by default, so the loser of the
 * race simply stores an equally valid token.
 */
@Component
class AccessTokenManager(
    private val authorizedClients: ServerOAuth2AuthorizedClientRepository,
    private val authorizedClientManager: ReactiveOAuth2AuthorizedClientManager,
) {
    private val log = LoggerFactory.getLogger(javaClass)

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
                if (!isExpired(client)) {
                    Mono.just(client.accessToken.tokenValue)
                } else if (client.refreshToken == null) {
                    destroyAndDegrade(auth, exchange)
                } else {
                    refresh(auth, client, exchange)
                }
            }

    private fun refresh(
        auth: OAuth2AuthenticationToken,
        client: OAuth2AuthorizedClient,
        exchange: ServerWebExchange,
    ): Mono<String> {
        val start = System.nanoTime()
        fun elapsedMs() = Duration.ofNanos(System.nanoTime() - start).toMillis()

        val request = OAuth2AuthorizeRequest.withAuthorizedClient(client)
            .principal(auth)
            .attribute(ServerWebExchange::class.java.name, exchange)
            .build()

        return authorizedClientManager.authorize(request)
            .singleOptional()
            .flatMap { maybeRefreshed ->
                val refreshed = maybeRefreshed.orElse(null)
                    ?: return@flatMap destroyAndDegrade(auth, exchange)
                Mono.just(refreshed.accessToken.tokenValue)
            }
            .onErrorResume { e ->
                if (e.isRefreshGrantRejection()) {
                    // degraded but self-healing (the session is destroyed below) — WARN per docs/LOGGING.md §5
                    log.logEvent(
                        Level.WARN, "token_refresh_failed", "failure",
                        "Token refresh rejected by the IdP (${refreshRejectionCode(e)}); treating the session as expired",
                        "error_code" to "idp_rejected",
                        "oauth2_error" to refreshRejectionCode(e),
                    )
                    destroyAndDegrade(auth, exchange)
                } else {
                    // Anything that is not a verdict on the grant — unreachable,
                    // timeout, 5xx/429 OAuth error, or garbage in a success body —
                    // proves nothing about the session. Keep it and let the relay
                    // answer 503.
                    log.logEvent(
                        Level.ERROR, "dependency_call_failed",
                        if (e is TimeoutException) "timeout" else "failure",
                        "Keycloak was unavailable during token refresh; the session is kept",
                        "dependency" to "keycloak",
                        "duration_ms" to elapsedMs(),
                        "error_code" to dependencyErrorCode(e),
                        cause = e,
                    )
                    Mono.error(IdpUnavailableException("The IdP could not refresh the access token", e))
                }
            }
    }

    private fun isExpired(client: OAuth2AuthorizedClient): Boolean =
        client.accessToken.expiresAt?.minus(EXPIRY_SKEW)?.isAfter(Instant.now()) == false

    private fun Throwable.isRefreshGrantRejection(): Boolean =
        oauth2ErrorCode() == OAuth2ErrorCodes.INVALID_GRANT ||
            oauth2ErrorCode() == OAuth2ErrorCodes.INVALID_CLIENT ||
            causeChain().any { cause ->
                when (cause) {
                    is TokenEndpointResponseStatusException -> cause.statusCode.isRefreshGrantRejectionStatus()
                    is WebClientResponseException -> cause.statusCode.value().isRefreshGrantRejectionStatus()
                    else -> false
                }
            }

    private fun dependencyErrorCode(e: Throwable): String =
        if (e is OAuth2AuthorizationException) e.error.errorCode else e.javaClass.simpleName

    private fun refreshRejectionCode(e: Throwable): String =
        e.oauth2ErrorCode()
            ?: e.causeChain()
                .firstNotNullOfOrNull { cause ->
                    when (cause) {
                        is TokenEndpointResponseStatusException -> "http_${cause.statusCode}"
                        is WebClientResponseException -> "http_${cause.statusCode.value()}"
                        else -> null
                    }
                }
            ?: e.javaClass.simpleName

    private fun Throwable.oauth2ErrorCode(): String? =
        (this as? OAuth2AuthorizationException)?.error?.errorCode

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause }

    private fun Int.isRefreshGrantRejectionStatus(): Boolean =
        this == 400 || this == 401

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

    companion object {
        /** Refresh slightly early so a token cannot expire mid-flight to the backend. */
        private val EXPIRY_SKEW: Duration = Duration.ofSeconds(30)
    }
}

/**
 * A token refresh failed because the IdP was unreachable, failing (5xx/429), or
 * unintelligible — a transient dependency outage, distinct from the IdP rejecting
 * the refresh token itself.
 */
class IdpUnavailableException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
