package dev.stackverse.gateway

import org.springframework.security.oauth2.client.OAuth2AuthorizedClient
import org.springframework.security.oauth2.core.OAuth2AccessToken
import org.springframework.security.oauth2.core.OAuth2RefreshToken
import org.springframework.session.ReactiveSessionRepository
import org.springframework.session.Session
import org.testcontainers.containers.GenericContainer
import org.testcontainers.containers.wait.strategy.Wait
import org.testcontainers.utility.MountableFile
import java.net.CookieManager
import java.net.CookiePolicy
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** The shared realm definition in infra/keycloak — walk up to the repo root. */
fun findRealmFile(): Path {
    var dir: Path? = Path.of("").toAbsolutePath()
    while (dir != null) {
        val candidate = dir.resolve("infra/keycloak/stackverse-realm.json")
        if (Files.exists(candidate)) {
            return candidate
        }
        dir = dir.parent
    }
    error("infra/keycloak/stackverse-realm.json not found in any parent directory")
}

/** Keycloak with the shared stackverse realm imported, ready when the realm answers. */
fun keycloakContainer(): GenericContainer<*> =
    GenericContainer("quay.io/keycloak/keycloak:26.6")
        .withCopyFileToContainer(
            MountableFile.forHostPath(findRealmFile()),
            "/opt/keycloak/data/import/stackverse-realm.json",
        )
        .withCommand("start-dev", "--import-realm")
        .withExposedPorts(8080)
        .waitingFor(Wait.forHttp("/realms/stackverse").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)))

fun redisContainer(): GenericContainer<*> =
    GenericContainer("redis:8-alpine").withExposedPorts(6379)

/** A browser-like HTTP client: keeps cookies, never follows redirects on its own. */
fun browserClient(cookies: CookieManager = CookieManager(null, CookiePolicy.ACCEPT_ALL)): HttpClient =
    HttpClient.newBuilder()
        .cookieHandler(cookies)
        .followRedirects(HttpClient.Redirect.NEVER)
        .build()

fun get(client: HttpClient, url: String, vararg headers: Pair<String, String>): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI.create(url)).GET()
    headers.forEach { (name, value) -> request.header(name, value) }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
}

fun post(client: HttpClient, url: String, body: String, vararg headers: Pair<String, String>): HttpResponse<String> {
    val request = HttpRequest.newBuilder(URI.create(url))
        .POST(HttpRequest.BodyPublishers.ofString(body))
        .header("Content-Type", "application/json")
    headers.forEach { (name, value) -> request.header(name, value) }
    return client.send(request.build(), HttpResponse.BodyHandlers.ofString())
}

fun setCookieValue(response: HttpResponse<String>, cookieName: String): String? =
    response.headers().allValues("set-cookie")
        .firstOrNull { it.startsWith("$cookieName=") }
        ?.let { it.substring(cookieName.length + 1, it.indexOf(';')) }

fun decodeJwtPayload(jwt: String): String {
    val payload = jwt.split(".")[1]
    return String(Base64.getUrlDecoder().decode(payload))
}

/**
 * Drives the real authorization code flow against the Testcontainers Keycloak,
 * shared by every test class that needs a logged-in gateway session: challenge at
 * the gateway, login form at Keycloak (cookies carried by hand — Keycloak marks
 * them Secure even over http, which the JDK cookie jar would then refuse to send
 * back), callback replayed against the gateway. Returns the XSRF-TOKEN issued
 * along the way.
 */
fun logIn(gatewayBase: String, client: HttpClient, username: String, password: String): String {
    val challenge = get(client, "$gatewayBase/auth/login")
    check(challenge.statusCode() == 302) { "expected a challenge redirect, got ${challenge.statusCode()}" }
    val xsrfToken = setCookieValue(challenge, "XSRF-TOKEN")
        ?: error("gateway did not issue an XSRF-TOKEN cookie")

    val keycloak = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).build()
    val loginPage = get(keycloak, challenge.headers().firstValue("location").orElseThrow())
    check(loginPage.statusCode() == 200) { "Keycloak login page returned ${loginPage.statusCode()}" }
    val formAction = Regex("action=\"([^\"]+)\"").find(loginPage.body())?.groupValues?.get(1)
        ?.replace("&amp;", "&")
        ?: error("no login form found on the Keycloak page")
    val keycloakCookies = loginPage.headers().allValues("set-cookie").joinToString("; ") { it.substringBefore(';') }

    val credentials = keycloak.send(
        HttpRequest.newBuilder(URI.create(formAction))
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    "username=${java.net.URLEncoder.encode(username, Charsets.UTF_8)}" +
                        "&password=${java.net.URLEncoder.encode(password, Charsets.UTF_8)}",
                ),
            )
            .header("Content-Type", "application/x-www-form-urlencoded")
            .header("Cookie", keycloakCookies)
            .build(),
        HttpResponse.BodyHandlers.ofString(),
    )
    check(credentials.statusCode() == 302) {
        "Keycloak login POST returned ${credentials.statusCode()}: ${credentials.body().take(500)}"
    }
    val callbackUrl = URI.create(credentials.headers().firstValue("location").orElseThrow())
    check(callbackUrl.toString().startsWith("http://localhost:8000/auth/callback")) {
        "unexpected callback location: $callbackUrl"
    }

    // Replay the callback against the gateway (the browser would hit localhost:8000).
    val callback = get(client, "$gatewayBase${callbackUrl.rawPath}?${callbackUrl.rawQuery}")
    check(callback.statusCode() == 302 && callback.headers().firstValue("location").orElse("") == "/") {
        "callback did not redirect home: ${callback.statusCode()}"
    }
    checkNotNull(setCookieValue(callback, "stackverse_session")) { "callback did not set the session cookie" }

    return xsrfToken
}

/** The attribute WebSessionServerOAuth2AuthorizedClientRepository keeps the tokens under. */
private const val AUTHORIZED_CLIENTS_ATTR =
    "org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository.AUTHORIZED_CLIENTS"

/**
 * Rewrites the stored session's tokens through the gateway's own session repository —
 * the moral equivalent of yarp's ticket-store surgery — to force the next /api call
 * down the refresh path (expired access token) and optionally make that refresh
 * unacceptable to the IdP (garbage refresh token).
 */
fun <S : Session> corruptStoredTokens(
    repository: ReactiveSessionRepository<S>,
    sessionId: String,
    breakRefreshToken: Boolean = false,
) {
    val session = repository.findById(sessionId).block() ?: error("session $sessionId not found in Redis")
    val clients: MutableMap<String, OAuth2AuthorizedClient> =
        session.getAttribute(AUTHORIZED_CLIENTS_ATTR) ?: error("session carries no authorized client")
    val client = clients["keycloak"] ?: error("no authorized client for the keycloak registration")
    val now = Instant.now()
    clients["keycloak"] = OAuth2AuthorizedClient(
        client.clientRegistration,
        client.principalName,
        OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            client.accessToken.tokenValue,
            now.minusSeconds(600),
            now.minusSeconds(300), // expired: the next relay must refresh
        ),
        if (breakRefreshToken) OAuth2RefreshToken("no-longer-a-refresh-token", now) else client.refreshToken,
    )
    session.setAttribute(AUTHORIZED_CLIENTS_ATTR, clients)
    repository.save(session).block()
}
