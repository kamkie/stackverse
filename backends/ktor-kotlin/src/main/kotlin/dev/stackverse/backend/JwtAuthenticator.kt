package dev.stackverse.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.crypto.RSASSAVerifier
import com.nimbusds.jose.jwk.JWKMatcher
import com.nimbusds.jose.jwk.JWKSelector
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.source.JWKSource
import com.nimbusds.jose.jwk.source.JWKSourceBuilder
import com.nimbusds.jose.proc.SecurityContext
import com.nimbusds.jwt.SignedJWT
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.header
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.slf4j.Logger
import org.slf4j.event.Level
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.util.Date
import kotlin.coroutines.cancellation.CancellationException

class JwtAuthenticator(private val config: Config, private val mapper: ObjectMapper, private val logger: Logger) {
    private val http = HttpClient.newBuilder().build()
    private val jwkSource: JWKSource<SecurityContext> by lazy {
        JWKSourceBuilder.create<SecurityContext>(URI(resolveJwksUri()).toURL()).build()
    }

    suspend fun authenticate(call: ApplicationCall): Identity? {
        val header = call.request.header(HttpHeaders.Authorization) ?: return null
        val raw = header.removePrefix("Bearer ").takeIf { header.startsWith("Bearer ") && it.isNotBlank() }
            ?: throwRejected()
        return try {
            withContext(Dispatchers.IO) { validate(raw) }
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            throwRejected()
        }
    }

    private fun validate(raw: String): Identity {
        val jwt = SignedJWT.parse(raw)
        val algorithm = jwt.header.algorithm
        if (algorithm !in setOf(JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512)) {
            throw IllegalArgumentException("unsupported JWT algorithm")
        }
        val keys = jwkSource.get(
            JWKSelector(JWKMatcher.Builder().keyID(jwt.header.keyID).build()),
            null,
        )
        val rsaKey = keys.filterIsInstance<RSAKey>().firstOrNull() ?: throw IllegalArgumentException("unknown JWT key")
        if (!jwt.verify(RSASSAVerifier(rsaKey.toRSAPublicKey()))) {
            throw IllegalArgumentException("invalid JWT signature")
        }
        val claims = jwt.jwtClaimsSet
        val now = Date()
        if (claims.issuer != config.issuerUri) throw IllegalArgumentException("invalid issuer")
        if (AUDIENCE !in claims.audience.orEmpty()) throw IllegalArgumentException("invalid audience")
        if (claims.expirationTime == null || claims.expirationTime.before(Date(now.time - 30_000))) {
            throw IllegalArgumentException("expired token")
        }
        claims.notBeforeTime?.let {
            if (it.after(Date(now.time + 30_000))) throw IllegalArgumentException("token not valid yet")
        }
        val username = claims.getStringClaim("preferred_username")?.takeIf { it.isNotBlank() }
            ?: throw IllegalArgumentException("missing preferred_username")
        val roles = ((claims.getJSONObjectClaim("realm_access")?.get("roles") as? Collection<*>).orEmpty())
            .filterIsInstance<String>()
        return Identity(
            username = username,
            name = claims.getStringClaim("name"),
            email = claims.getStringClaim("email"),
            roles = roles,
        )
    }

    private fun throwRejected(): Nothing {
        logger.logEvent(Level.INFO, "jwt_validation_failed", "failure", "Rejected a bearer token", "error_code" to "invalid_token")
        throw ApiProblem(HttpStatusCode.Unauthorized, "Unauthorized", detail = "Missing or invalid bearer token.")
    }

    private fun resolveJwksUri(): String {
        if (config.jwksUri.isNotBlank()) return config.jwksUri
        val request = HttpRequest.newBuilder(URI.create("${config.issuerUri}/.well-known/openid-configuration")).GET().build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() != 200) {
            throw IllegalStateException("OIDC discovery answered ${response.statusCode()}")
        }
        return mapper.readTree(response.body()).path("jwks_uri").asText().takeIf { it.isNotBlank() }
            ?: throw IllegalStateException("OIDC discovery response did not include jwks_uri")
    }
}
