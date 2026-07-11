package dev.stackverse.backend.config

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.security.oauth2.jwt.JwtValidationException
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Date

class SecurityConfigTest {

    private lateinit var server: HttpServer
    private lateinit var signingKey: RSAKey
    private lateinit var issuer: String

    @BeforeEach
    fun startJwksServer() {
        signingKey = RSAKeyGenerator(2048).keyID("stackverse-test-key").generate()
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        issuer = "http://127.0.0.1:${server.address.port}"
        server.createContext("/jwks") { exchange ->
            respondJson(exchange, JWKSet(signingKey.toPublicJWK()).toString())
        }
        server.createContext("/.well-known/openid-configuration") { exchange ->
            respondJson(
                exchange,
                """{"issuer":"$issuer","jwks_uri":"$issuer/jwks","id_token_signing_alg_values_supported":["RS256"]}""",
            )
        }
    }

    @AfterEach
    fun stopJwksServer() {
        server.stop(0)
    }

    @Test
    fun `explicit JWKS decoder validates signature issuer and audience`() {
        val decoder = SecurityConfig().jwtDecoder(
            OidcProperties(issuerUri = issuer, jwksUri = "$issuer/jwks", audience = "stackverse-api"),
        )

        val decoded = decoder.decode(signedToken(issuer = issuer, audience = "stackverse-api"))

        assertThat(decoded.subject).isEqualTo("alice")
        assertThat(decoded.audience).containsExactly("stackverse-api")
        assertThatThrownBy { decoder.decode(signedToken(issuer = issuer, audience = "wrong-api")) }
            .isInstanceOf(JwtValidationException::class.java)
            .hasMessageContaining("required audience")
        assertThatThrownBy { decoder.decode(signedToken(issuer = "$issuer/wrong", audience = "stackverse-api")) }
            .isInstanceOf(JwtValidationException::class.java)
    }

    @Test
    fun `blank JWKS URI uses issuer discovery and applies the same validators`() {
        val decoder = SecurityConfig().jwtDecoder(
            OidcProperties(issuerUri = issuer, jwksUri = " ", audience = "stackverse-api"),
        )

        val decoded = decoder.decode(signedToken(issuer = issuer, audience = "stackverse-api"))

        assertThat(decoded.getClaimAsString("preferred_username")).isEqualTo("alice")
        assertThat(decoded.getClaimAsMap("realm_access")).containsEntry("roles", listOf("moderator", "admin"))
    }

    private fun signedToken(issuer: String, audience: String): String {
        val now = Instant.now()
        val claims = JWTClaimsSet.Builder()
            .issuer(issuer)
            .audience(audience)
            .subject("alice")
            .issueTime(Date.from(now.minusSeconds(5)))
            .expirationTime(Date.from(now.plusSeconds(300)))
            .claim("preferred_username", "alice")
            .claim("realm_access", mapOf("roles" to listOf("moderator", "admin")))
            .build()
        val token = SignedJWT(
            JWSHeader.Builder(JWSAlgorithm.RS256)
                .type(JOSEObjectType.JWT)
                .keyID(signingKey.keyID)
                .build(),
            claims,
        )
        token.sign(RSASSASigner(signingKey))
        return token.serialize()
    }

    private fun respondJson(exchange: HttpExchange, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        exchange.responseHeaders.add("Content-Type", "application/json")
        exchange.sendResponseHeaders(200, bytes.size.toLong())
        exchange.responseBody.use { it.write(bytes) }
    }
}
