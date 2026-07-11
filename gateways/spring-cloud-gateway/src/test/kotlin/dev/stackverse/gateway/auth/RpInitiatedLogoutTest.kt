package dev.stackverse.gateway.auth

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository
import org.springframework.security.oauth2.core.AuthorizationGrantType
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import java.net.InetSocketAddress
import java.net.URLDecoder
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference

class RpInitiatedLogoutTest {

    @Test
    fun `logout posts the confidential client form and tolerates an idp rejection`() {
        val requestBody = AtomicReference<String>()
        val requestContentType = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/end-session") { exchange ->
            requestContentType.set(exchange.requestHeaders.getFirst("Content-Type"))
            requestBody.set(String(exchange.requestBody.readAllBytes(), Charsets.UTF_8))
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()

        try {
            val endpoint = "http://127.0.0.1:${server.address.port}/end-session"
            val logout = RpInitiatedLogout(registrations(endpoint))

            assertDoesNotThrow {
                logout.logout("refresh token").block(Duration.ofSeconds(5))
            }

            assertEquals("application/x-www-form-urlencoded", requestContentType.get())
            assertEquals(
                mapOf(
                    "client_id" to "stackverse-gateway",
                    "client_secret" to "dummy-client-secret",
                    "refresh_token" to "refresh token",
                ),
                decodeForm(requestBody.get()),
            )
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `logout remains best effort when discovery metadata has no end session endpoint`() {
        val logout = RpInitiatedLogout(registrations(endSessionEndpoint = null))

        assertDoesNotThrow {
            logout.logout("dummy-refresh-token").block(Duration.ofSeconds(5))
        }
    }

    private fun registrations(endSessionEndpoint: String?): InMemoryReactiveClientRegistrationRepository {
        val builder = ClientRegistration.withRegistrationId("keycloak")
            .clientId("stackverse-gateway")
            .clientSecret("dummy-client-secret")
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost:8000/auth/callback")
            .authorizationUri("http://localhost:8180/realms/stackverse/protocol/openid-connect/auth")
            .tokenUri("http://localhost:8180/realms/stackverse/protocol/openid-connect/token")
        if (endSessionEndpoint != null) {
            builder.providerConfigurationMetadata(mapOf("end_session_endpoint" to endSessionEndpoint))
        }
        return InMemoryReactiveClientRegistrationRepository(builder.build())
    }

    private fun decodeForm(body: String): Map<String, String> =
        body.split('&').associate { field ->
            val parts = field.split('=', limit = 2)
            URLDecoder.decode(parts[0], Charsets.UTF_8) to URLDecoder.decode(parts[1], Charsets.UTF_8)
        }
}
