package dev.stackverse.gateway.relay

import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.ClientAuthorizationException
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.security.oauth2.core.user.OAuth2User
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Instant

class AccessTokenManagerTest {

    @Test
    fun `token endpoint 400 with unparsable oauth body destroys session and degrades`() {
        assertRefreshRejected(statusCode = 400, errorCode = "invalid_token_response")
    }

    @Test
    fun `token endpoint 401 with non invalid-grant oauth error destroys session and degrades`() {
        assertRefreshRejected(statusCode = 401, errorCode = "server_error")
    }

    @Test
    fun `token endpoint 500 parsing failure keeps the session and surfaces idp outage`() {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/bookmarks").build())
        val session = exchange.session.block()!!
        val manager = AccessTokenManager(
            StaticAuthorizedClientRepository(expiredClient()),
            FailingAuthorizedClientManager(refreshFailure(statusCode = 500, errorCode = "invalid_token_response")),
        )

        assertThrows(IdpUnavailableException::class.java) {
            manager.accessToken(authentication(), exchange).block()
        }

        assertFalse(session.isExpired)
    }

    private fun assertRefreshRejected(statusCode: Int, errorCode: String) {
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.get("/api/v1/bookmarks").build())
        val session = exchange.session.block()!!
        val manager = AccessTokenManager(
            StaticAuthorizedClientRepository(expiredClient()),
            FailingAuthorizedClientManager(refreshFailure(statusCode, errorCode)),
        )

        val token = manager.accessToken(authentication(), exchange).blockOptional()

        assertTrue(token.isEmpty)
        assertTrue(session.isExpired)
    }

    private fun refreshFailure(statusCode: Int, errorCode: String): ClientAuthorizationException {
        val error = OAuth2Error(errorCode, "token endpoint failed", null)
        val statusFailure = OAuth2AuthorizationException(
            error,
            "token endpoint failed",
            TokenEndpointResponseStatusException(statusCode, RuntimeException("body")),
        )
        return ClientAuthorizationException(error, "keycloak", statusFailure)
    }

    private fun authentication(): OAuth2AuthenticationToken {
        val authorities = listOf(SimpleGrantedAuthority("ROLE_user"))
        val principal: OAuth2User = DefaultOAuth2User(
            authorities,
            mapOf("sub" to "demo", "preferred_username" to "demo"),
            "preferred_username",
        )
        return OAuth2AuthenticationToken(principal, authorities, "keycloak")
    }

    private fun expiredClient(): OAuth2AuthorizedClient {
        val now = Instant.now()
        return OAuth2AuthorizedClient(
            clientRegistration(),
            "demo",
            OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "expired-access-token",
                now.minusSeconds(600),
                now.minusSeconds(300),
            ),
            OAuth2RefreshToken("refresh-token", now.minusSeconds(600)),
        )
    }

    private fun clientRegistration(): ClientRegistration =
        ClientRegistration.withRegistrationId("keycloak")
            .clientId("stackverse-gateway")
            .clientSecret("stackverse-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost:8000/auth/callback")
            .authorizationUri("http://localhost:8180/realms/stackverse/protocol/openid-connect/auth")
            .tokenUri("http://localhost:8180/realms/stackverse/protocol/openid-connect/token")
            .build()

    private class StaticAuthorizedClientRepository(
        private val client: OAuth2AuthorizedClient,
    ) : ServerOAuth2AuthorizedClientRepository {
        @Suppress("UNCHECKED_CAST")
        override fun <T : OAuth2AuthorizedClient> loadAuthorizedClient(
            clientRegistrationId: String,
            principal: Authentication,
            exchange: ServerWebExchange,
        ): Mono<T> = Mono.just(client as T)

        override fun saveAuthorizedClient(
            authorizedClient: OAuth2AuthorizedClient,
            principal: Authentication,
            exchange: ServerWebExchange,
        ): Mono<Void> = Mono.empty()

        override fun removeAuthorizedClient(
            clientRegistrationId: String,
            principal: Authentication,
            exchange: ServerWebExchange,
        ): Mono<Void> = Mono.empty()
    }

    private class FailingAuthorizedClientManager(
        private val failure: Throwable,
    ) : ReactiveOAuth2AuthorizedClientManager {
        override fun authorize(authorizeRequest: OAuth2AuthorizeRequest): Mono<OAuth2AuthorizedClient> =
            Mono.error(failure)
    }
}
