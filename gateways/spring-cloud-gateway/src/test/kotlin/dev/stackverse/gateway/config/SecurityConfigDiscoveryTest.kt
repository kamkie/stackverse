package dev.stackverse.gateway.config

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import java.net.URI

class SecurityConfigDiscoveryTest {

    @Test
    fun `empty discovery response fails startup explicitly`() {
        withDiscoveryResponse(status = 204) { internalIssuer ->
            val failure = assertThrows(IllegalStateException::class.java) {
                SecurityConfig().clientRegistrationRepository(gateway(internalIssuer = internalIssuer))
            }

            assertTrue(failure.message!!.contains("empty OIDC discovery document"))
        }
    }

    @Test
    fun `discovery document without issuer fails startup explicitly`() {
        withDiscoveryResponse(body = "{}") { internalIssuer ->
            val failure = assertThrows(IllegalStateException::class.java) {
                SecurityConfig().clientRegistrationRepository(gateway(internalIssuer = internalIssuer))
            }

            assertTrue(failure.message!!.contains("carries no issuer"))
        }
    }

    @Test
    fun `announced issuer must match the browser facing issuer`() {
        withDiscoveryResponse(body = """{"issuer":"https://wrong.example/realms/stackverse"}""") { internalIssuer ->
            val failure = assertThrows(IllegalStateException::class.java) {
                SecurityConfig().clientRegistrationRepository(gateway(internalIssuer = internalIssuer))
            }

            assertTrue(failure.message!!.contains("but OIDC_ISSUER_URI is"))
        }
    }

    @Test
    fun `discovery requires the rp initiated logout endpoint`() {
        val issuer = "https://idp.example/realms/stackverse"
        val metadata =
            """
            {
              "issuer": "$issuer",
              "authorization_endpoint": "$issuer/protocol/openid-connect/auth",
              "token_endpoint": "$issuer/protocol/openid-connect/token",
              "jwks_uri": "$issuer/protocol/openid-connect/certs",
              "subject_types_supported": ["public"],
              "id_token_signing_alg_values_supported": ["RS256"]
            }
            """.trimIndent()

        withDiscoveryResponse(body = metadata) { internalIssuer ->
            val failure = assertThrows(IllegalArgumentException::class.java) {
                SecurityConfig().clientRegistrationRepository(
                    gateway(issuer = issuer, internalIssuer = internalIssuer),
                )
            }

            assertTrue(failure.message!!.contains("carries no end_session_endpoint"))
        }
    }

    private fun withDiscoveryResponse(
        status: Int = 200,
        body: String? = null,
        test: (internalIssuer: String) -> Unit,
    ) {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/.well-known/openid-configuration") { exchange ->
            val bytes = body?.toByteArray(Charsets.UTF_8)
            if (bytes != null) {
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            } else {
                exchange.sendResponseHeaders(status, -1)
                exchange.close()
            }
        }
        server.start()

        try {
            test("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    private fun gateway(
        issuer: String = "https://idp.example/realms/stackverse",
        internalIssuer: String,
    ) = GatewayProperties(
        backendUrl = URI("http://localhost:8080"),
        frontendUrl = null,
        publicUrl = URI("http://localhost:8000"),
        oidc = GatewayProperties.Oidc(
            issuerUri = issuer,
            internalIssuerUri = internalIssuer,
            clientId = "stackverse-gateway",
            clientSecret = "dummy-client-secret",
        ),
    )
}
