package dev.stackverse.gateway.config

import com.sun.net.httpserver.HttpServer
import dev.stackverse.gateway.relay.TokenEndpointResponseStatusException
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import java.net.InetSocketAddress
import java.time.Instant

class RefreshTokenResponseClientTest {

    @Test
    fun `refresh client preserves 400 status when oauth error body cannot be parsed`() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/token") { exchange ->
            val body = "not-json".toByteArray()
            exchange.responseHeaders.add("Content-Type", "text/plain")
            exchange.sendResponseHeaders(400, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        server.start()

        try {
            val tokenUri = "http://127.0.0.1:${server.address.port}/token"
            val failure = assertThrows(OAuth2AuthorizationException::class.java) {
                SecurityConfig().refreshTokenResponseClient()
                    .getTokenResponse(refreshRequest(tokenUri))
                    .block()
            }

            val statusFailure = failure.causeChain()
                .filterIsInstance<TokenEndpointResponseStatusException>()
                .firstOrNull()
            assertNotNull(statusFailure)
            assertEquals(400, statusFailure!!.statusCode)
        } finally {
            server.stop(0)
        }
    }

    private fun refreshRequest(tokenUri: String): OAuth2RefreshTokenGrantRequest {
        val now = Instant.now()
        return OAuth2RefreshTokenGrantRequest(
            clientRegistration(tokenUri),
            OAuth2AccessToken(
                OAuth2AccessToken.TokenType.BEARER,
                "expired-access-token",
                now.minusSeconds(600),
                now.minusSeconds(300),
            ),
            OAuth2RefreshToken("refresh-token", now.minusSeconds(600)),
        )
    }

    private fun clientRegistration(tokenUri: String): ClientRegistration =
        ClientRegistration.withRegistrationId("keycloak")
            .clientId("stackverse-gateway")
            .clientSecret("stackverse-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost:8000/auth/callback")
            .authorizationUri("http://localhost:8180/realms/stackverse/protocol/openid-connect/auth")
            .tokenUri(tokenUri)
            .build()

    private fun Throwable.causeChain(): Sequence<Throwable> =
        generateSequence(this) { it.cause }
}
