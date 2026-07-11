package dev.stackverse.gateway.auth

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.mock.http.server.reactive.MockServerHttpRequest
import org.springframework.mock.web.server.MockServerWebExchange
import org.springframework.security.core.Authentication
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono
import java.time.Instant

class AuthControllerTest {

    @Test
    fun `logout destroys a local session even when no refresh token exists`() {
        val registration = clientRegistration()
        val controller = AuthController(
            StaticAuthorizedClientRepository(authorizedClientWithoutRefreshToken(registration)),
            RpInitiatedLogout(InMemoryReactiveClientRegistrationRepository(registration)),
        )
        val authentication = authentication()
        val exchange = MockServerWebExchange.from(MockServerHttpRequest.post("/auth/logout").build())
            .mutate()
            .principal(Mono.just(authentication))
            .build()
        val session = exchange.session.block()!!

        controller.logout(exchange).block()

        assertTrue(session.isExpired)
    }

    private fun authentication(): OAuth2AuthenticationToken {
        val authorities = listOf(SimpleGrantedAuthority("ROLE_user"))
        val principal = DefaultOAuth2User(
            authorities,
            mapOf("sub" to "demo", "preferred_username" to "demo"),
            "preferred_username",
        )
        return OAuth2AuthenticationToken(principal, authorities, "keycloak")
    }

    private fun authorizedClientWithoutRefreshToken(registration: ClientRegistration): OAuth2AuthorizedClient {
        val now = Instant.now()
        return OAuth2AuthorizedClient(
            registration,
            "demo",
            OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "access-token",
                now.minusSeconds(30),
                now.plusSeconds(300),
            ),
        )
    }

    private fun clientRegistration(): ClientRegistration =
        ClientRegistration.withRegistrationId("keycloak")
            .clientId("stackverse-gateway")
            .clientSecret("dummy-client-secret")
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
}
