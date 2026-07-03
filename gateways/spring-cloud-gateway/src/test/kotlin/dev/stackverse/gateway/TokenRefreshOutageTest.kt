package dev.stackverse.gateway

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.session.data.redis.ReactiveRedisSessionRepository
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.CookieManager
import java.net.CookiePolicy

/**
 * The IdP-unreachable refresh path (docs/ARCHITECTURE.md): a transient Keycloak
 * outage must fail the request with a 503 problem document while the session —
 * whose refresh token may still be perfectly valid — survives. Gets its own
 * containers (and Spring context) because it kills the Keycloak container.
 *
 * This fixture also runs the gateway through `OIDC_INTERNAL_ISSUER_URI`: Keycloak
 * announces the issuer as `localhost` while the gateway's own IdP calls are
 * re-based onto `127.0.0.1` — the compose-critical endpoint-rebase path
 * (docs: gateways/README.md). The login below fails outright if rebasing or the
 * startup issuer check regress.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TokenRefreshOutageTest {

    companion object {
        // the announced issuer must be known before startup, so the host port is fixed
        private val keycloakPort = freePort()
        private val keycloak = keycloakContainer(
            fixedHostPort = keycloakPort,
            hostname = "http://localhost:$keycloakPort",
        )
        private val redis = redisContainer()
        private val backend = StubBackend()

        init {
            keycloak.start()
            redis.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { "redis://localhost:${redis.getMappedPort(6379)}" }
            registry.add("stackverse.oidc.issuer-uri") { "http://localhost:$keycloakPort/realms/stackverse" }
            registry.add("stackverse.oidc.internal-issuer-uri") { "http://127.0.0.1:$keycloakPort/realms/stackverse" }
            registry.add("stackverse.backend-url") { backend.url }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            backend.close()
        }
    }

    @LocalServerPort
    private var port = 0

    @Autowired
    private lateinit var sessions: ReactiveRedisSessionRepository

    @Test
    fun `idp outage during refresh fails the request but keeps the session`() {
        val base = "http://localhost:$port"
        val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val client = browserClient(cookies)
        logIn(base, client, "demo", "demo")

        // Force the next /api call down the refresh path: rewrite the stored access
        // token's expiry into the past, through the gateway's own session repository.
        val sessionId = cookies.cookieStore.cookies.first { it.name == "stackverse_session" }.value
        corruptStoredTokens(sessions, sessionId)

        keycloak.stop()

        // The request fails explicitly — a 503 problem document, not an unhandled 500
        // and not an anonymous relay the user never asked for.
        val response = get(client, "$base/api/v1/bookmarks")
        assertEquals(503, response.statusCode())
        assertEquals("application/problem+json", response.headers().firstValue("content-type").orElse(""))
        assertTrue(response.body().contains("\"status\":503"))

        // The session survived the outage: the IdP said nothing about it.
        val session = get(client, "$base/auth/session")
        assertEquals(200, session.statusCode())
        assertTrue(session.body().contains("\"authenticated\":true"))
        assertTrue(session.body().contains("\"username\":\"demo\""))
    }
}
