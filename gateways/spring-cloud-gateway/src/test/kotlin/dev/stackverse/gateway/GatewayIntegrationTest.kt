package dev.stackverse.gateway

import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.session.data.redis.ReactiveRedisSessionRepository
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import java.net.CookieManager
import java.net.CookiePolicy

/**
 * Boots the real dependency set — Keycloak with the shared stackverse realm from
 * infra/keycloak, Redis for sessions, a recording stub backend — and drives the
 * gateway over HTTP like a browser would: the real authorization code flow, token
 * relay, CSRF, refresh, and logout.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class GatewayIntegrationTest {

    companion object {
        private val keycloak = keycloakContainer()
        private val redis = redisContainer()
        private val backend = StubBackend()

        init {
            // the gateway resolves the issuer metadata at startup, so the IdP must be
            // up before the Spring context refreshes
            keycloak.start()
            redis.start()
        }

        @JvmStatic
        @DynamicPropertySource
        fun properties(registry: DynamicPropertyRegistry) {
            registry.add("spring.data.redis.url") { "redis://localhost:${redis.getMappedPort(6379)}" }
            registry.add("stackverse.oidc.issuer-uri") {
                "http://localhost:${keycloak.getMappedPort(8080)}/realms/stackverse"
            }
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

    private val base get() = "http://localhost:$port"

    @Test
    fun `session endpoint reports unauthenticated without a session`() {
        val response = get(browserClient(), "$base/auth/session")

        assertEquals(200, response.statusCode())
        assertTrue(response.body().contains("\"authenticated\":false"))
        assertTrue(!response.body().contains("username"))
    }

    @Test
    fun `anonymous api requests relay without a bearer token`() {
        // The spec's public surface (public bookmark feeds, message reads) must work
        // logged-out: the gateway relays and the backend decides per endpoint. A
        // client-supplied Authorization header must be stripped, not relayed — the
        // gateway session is the only source of upstream identity.
        val response = get(
            browserClient(), "$base/api/v2/bookmarks?visibility=public",
            "Authorization" to "Bearer forged-by-the-client",
        )

        assertEquals(200, response.statusCode())
        assertEquals("", backend.lastAuthorization)
        assertEquals("", backend.lastCookie)
    }

    @Test
    fun `anonymous state-changing requests still require the csrf header`() {
        val response = post(browserClient(), "$base/api/v1/bookmarks", """{"url":"https://example.com"}""")

        assertEquals(403, response.statusCode())
        assertEquals("application/problem+json", response.headers().firstValue("content-type").orElse(""))
        assertTrue(response.body().contains("\"status\":403"))
    }

    @Test
    fun `login redirects to keycloak with code flow and pkce`() {
        val response = get(browserClient(), "$base/auth/login")

        assertEquals(302, response.statusCode())
        val location = response.headers().firstValue("location").orElse("")
        assertTrue(
            location.startsWith("http://localhost:${keycloak.getMappedPort(8080)}/realms/stackverse/protocol/openid-connect/auth"),
            "unexpected challenge location: $location",
        )
        assertTrue(location.contains("response_type=code"))
        assertTrue(location.contains("code_challenge_method=S256"))
        // the ':' and '/' of the value are legal raw in a query string — compare decoded
        val redirectUri = location.substringAfter("redirect_uri=").substringBefore("&")
        assertEquals("http://localhost:8000/auth/callback", java.net.URLDecoder.decode(redirectUri, Charsets.UTF_8))
    }

    // Cookie rules pinned in docs/ARCHITECTURE.md: the session cookie is HttpOnly,
    // SameSite=Lax; the CSRF cookie is deliberately readable (the SPA must echo it);
    // both are Secure only outside local dev — i.e. not here, over http.
    @Test
    fun `contract cookies carry the contract attributes`() {
        // /auth/login creates the pre-login session (authorization request) and
        // issues the CSRF token, so both Set-Cookie headers ride one response
        val response = get(browserClient(), "$base/auth/login")

        // attribute names are case-insensitive per RFC 6265 (Netty writes "HTTPOnly")
        val session = rawSetCookie(response, "stackverse_session")?.lowercase()
            ?: fail("no stackverse_session Set-Cookie on the login challenge")
        assertTrue(session.contains("httponly"), session)
        assertTrue(session.contains("samesite=lax"), session)
        assertTrue(session.contains("path=/"), session)
        assertTrue(!session.contains("secure"), session)

        val xsrf = rawSetCookie(response, "XSRF-TOKEN")?.lowercase()
            ?: fail("no XSRF-TOKEN Set-Cookie on the login challenge")
        assertTrue(!xsrf.contains("httponly"), xsrf)
        assertTrue(xsrf.contains("samesite=lax"), xsrf)
        assertTrue(xsrf.contains("path=/"), xsrf)
        assertTrue(!xsrf.contains("secure"), xsrf)
    }

    // A failed callback is expected client/IdP behavior (contract: redirect to /,
    // never a 5xx — docs/ARCHITECTURE.md): the user pressed Cancel on the Keycloak
    // form, or the correlation state is stale or replayed.
    @Test
    fun `failed callback redirects home without a session`() {
        for (callbackPath in listOf(
            "/auth/callback?error=access_denied&state=whatever", // user cancelled at the IdP
            "/auth/callback?code=fake-code&state=not-a-real-state", // stale/invalid correlation
        )) {
            val client = browserClient()
            val response = get(client, "$base$callbackPath")

            assertEquals(302, response.statusCode(), "for $callbackPath")
            assertEquals("/", response.headers().firstValue("location").orElse(""), "for $callbackPath")

            val session = get(client, "$base/auth/session")
            assertTrue(session.body().contains("\"authenticated\":false"), "for $callbackPath")
        }
    }

    @Test
    fun `full journey login relay csrf logout`() {
        val client = browserClient()

        // --- Log in: /auth/login → Keycloak form → callback → session cookie.
        val xsrfToken = logIn(base, client, "demo", "demo")

        val session = get(client, "$base/auth/session")
        assertTrue(session.body().contains("\"authenticated\":true"))
        assertTrue(session.body().contains("\"username\":\"demo\""))

        // --- Token relay: GET /api reaches the backend with a Bearer token.
        val list = get(client, "$base/api/v1/bookmarks")
        assertEquals(200, list.statusCode())
        assertEquals("/api/v1/bookmarks", backend.lastPath)
        assertTrue(backend.lastAuthorization.startsWith("Bearer "), "no bearer token relayed")
        assertEquals("", backend.lastCookie) // browser cookies never leave the gateway
        val payload = decodeJwtPayload(backend.lastAuthorization.removePrefix("Bearer "))
        assertTrue(payload.contains("\"preferred_username\":\"demo\""))
        assertTrue(payload.contains("stackverse-api"), "audience missing: $payload")

        // --- CSRF: state-changing requests need the double-submit header.
        val missingHeader = post(client, "$base/api/v1/bookmarks", """{"url":"https://example.com"}""")
        assertEquals(403, missingHeader.statusCode())
        assertEquals("application/problem+json", missingHeader.headers().firstValue("content-type").orElse(""))

        val mismatch = post(
            client, "$base/api/v1/bookmarks", """{"url":"https://example.com"}""",
            "X-XSRF-TOKEN" to "not-the-cookie-value",
        )
        assertEquals(403, mismatch.statusCode())

        val valid = post(
            client, "$base/api/v1/bookmarks", """{"url":"https://example.com"}""",
            "X-XSRF-TOKEN" to xsrfToken,
        )
        assertEquals(200, valid.statusCode())
        assertTrue(backend.lastAuthorization.startsWith("Bearer "))
        assertEquals("", backend.lastCsrfHeader) // consumed at the gateway

        // --- Logout destroys the session and answers 204.
        val logout = client.send(
            java.net.http.HttpRequest.newBuilder(java.net.URI.create("$base/auth/logout"))
                .POST(java.net.http.HttpRequest.BodyPublishers.noBody())
                .build(),
            java.net.http.HttpResponse.BodyHandlers.ofString(),
        )
        assertEquals(204, logout.statusCode())

        val afterLogout = get(client, "$base/auth/session")
        assertTrue(afterLogout.body().contains("\"authenticated\":false"))

        // The dead session no longer yields a token — the relay degrades to anonymous.
        val apiAfterLogout = get(client, "$base/api/v1/bookmarks")
        assertEquals(200, apiAfterLogout.statusCode())
        assertEquals("", backend.lastAuthorization)
    }

    @Test
    fun `expired access token is refreshed transparently`() {
        val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val client = browserClient(cookies)
        logIn(base, client, "demo", "demo")

        get(client, "$base/api/v1/bookmarks")
        val originalToken = backend.lastAuthorization

        val sessionId = cookies.cookieStore.cookies.first { it.name == "stackverse_session" }.value
        corruptStoredTokens(sessions, sessionId)

        // The relay notices the expired access token, refreshes against Keycloak,
        // and the request succeeds with a fresh token — invisible to the client.
        val response = get(client, "$base/api/v1/bookmarks")
        assertEquals(200, response.statusCode())
        assertTrue(backend.lastAuthorization.startsWith("Bearer "))
        assertNotEquals(originalToken, backend.lastAuthorization)

        // and the session is still alive
        val session = get(client, "$base/auth/session")
        assertTrue(session.body().contains("\"authenticated\":true"))
    }

    @Test
    fun `rejected refresh destroys the session and degrades to anonymous`() {
        val cookies = CookieManager(null, CookiePolicy.ACCEPT_ALL)
        val client = browserClient(cookies)
        logIn(base, client, "demo", "demo")

        val sessionId = cookies.cookieStore.cookies.first { it.name == "stackverse_session" }.value
        corruptStoredTokens(sessions, sessionId, breakRefreshToken = true)

        // Keycloak answers 400 invalid_grant — an authoritative verdict: the session
        // is destroyed and the request relays anonymously, not a 5xx.
        val response = get(client, "$base/api/v1/bookmarks")
        assertEquals(200, response.statusCode())
        assertEquals("", backend.lastAuthorization)

        val session = get(client, "$base/auth/session")
        assertTrue(session.body().contains("\"authenticated\":false"))
        assertTrue(sessions.findById(sessionId).blockOptional().isEmpty, "the dead session must be gone from Redis")
    }
}
