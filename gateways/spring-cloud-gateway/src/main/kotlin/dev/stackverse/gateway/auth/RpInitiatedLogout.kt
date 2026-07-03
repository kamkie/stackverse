package dev.stackverse.gateway.auth

import dev.stackverse.gateway.common.logEvent
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.http.MediaType
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.BodyInserters
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * Ends the user's SSO session at the IdP. The gateway contract requires
 * POST /auth/logout to answer 204 rather than bounce the browser through the IdP,
 * so RP-initiated logout happens server-to-server: Keycloak's end_session endpoint
 * accepts a confidential-client POST with the refresh token and tears down the SSO
 * session without any redirect.
 *
 * Best effort throughout: an unreachable or unhappy IdP must never keep the local
 * session alive — the caller destroys it regardless, and Keycloak's SSO session
 * still times out on its own. The returned Mono never errors.
 */
@Component
class RpInitiatedLogout(
    private val clientRegistrations: ReactiveClientRegistrationRepository,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val webClient = WebClient.create()

    fun logout(refreshToken: String): Mono<Void> =
        clientRegistrations.findByRegistrationId("keycloak")
            .flatMap { registration ->
                val endSessionEndpoint =
                    registration.providerDetails.configurationMetadata["end_session_endpoint"] as? String
                        ?: return@flatMap Mono.error(IllegalStateException("IdP metadata carries no end_session_endpoint"))
                webClient.post()
                    .uri(endSessionEndpoint)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(
                        BodyInserters.fromFormData("client_id", registration.clientId)
                            .with("client_secret", registration.clientSecret)
                            .with("refresh_token", refreshToken),
                    )
                    .exchangeToMono { response ->
                        if (!response.statusCode().is2xxSuccessful) {
                            log.logEvent(
                                Level.WARN, "idp_logout_failed", "failure",
                                "IdP logout returned ${response.statusCode().value()}; local session destroyed anyway",
                                "error_code" to "idp_rejected",
                                "idp_status" to response.statusCode().value(),
                            )
                        }
                        response.releaseBody()
                    }
            }
            .timeout(Duration.ofSeconds(30))
            .onErrorResume { e ->
                log.logEvent(
                    Level.WARN, "idp_logout_failed", "failure",
                    "IdP logout failed; local session destroyed anyway",
                    "error_code" to "idp_unreachable",
                    cause = e,
                )
                Mono.empty()
            }
}
