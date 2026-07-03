package dev.stackverse.gateway.web

import dev.stackverse.gateway.common.Problems
import dev.stackverse.gateway.common.logEvent
import dev.stackverse.gateway.common.sanitizeForLog
import dev.stackverse.gateway.config.GatewayProperties
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.core.Ordered
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import reactor.core.publisher.Mono
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.HexFormat

/** Cookie and header names of the contract's CSRF mechanism (docs/ARCHITECTURE.md). */
object Csrf {
    const val COOKIE_NAME = "XSRF-TOKEN"
    const val HEADER_NAME = "X-XSRF-TOKEN"
}

/**
 * Double-submit CSRF protection, as pinned in docs/ARCHITECTURE.md: the gateway issues
 * a JavaScript-readable XSRF-TOKEN cookie, and state-changing /api requests must echo
 * its value in an X-XSRF-TOKEN header. A cross-site attacker can make the browser send
 * the cookie but cannot read it, so it cannot forge the header.
 */
@Component
class CsrfWebFilter(private val gateway: GatewayProperties) : WebFilter, Ordered {

    private val log = LoggerFactory.getLogger(javaClass)
    private val random = SecureRandom()

    /**
     * In front of Spring Security's WebFilterChainProxy (order -100): its filters
     * complete responses themselves (the /auth/login challenge redirect, the
     * callback), and the cookie must ride those too.
     */
    override fun getOrder(): Int = -150

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> {
        issueToken(exchange)
        if (!isValid(exchange.request)) {
            // expected client behavior and a security signal — never above INFO (docs/LOGGING.md §3)
            log.logEvent(
                Level.INFO, "csrf_validation_failed", "denied",
                "Rejected a state-changing /api request without a matching CSRF header",
                "method" to sanitizeForLog(exchange.request.method.name()),
                // the decoded path is client-controlled input (§6)
                "path" to sanitizeForLog(exchange.request.path.value()),
            )
            return Problems.write(
                exchange, HttpStatus.FORBIDDEN,
                "Forbidden", "Missing or mismatched ${Csrf.HEADER_NAME} header.",
            )
        }
        return chain.filter(exchange)
    }

    /** Issues the readable double-submit cookie to any browser that lacks one. */
    private fun issueToken(exchange: ServerWebExchange) {
        if (exchange.request.cookies.getFirst(Csrf.COOKIE_NAME) != null) {
            return
        }
        val token = HexFormat.of().withUpperCase().formatHex(ByteArray(16).also(random::nextBytes))
        exchange.response.addCookie(
            ResponseCookie.from(Csrf.COOKIE_NAME, token)
                .httpOnly(false) // the SPA must be able to read it
                .secure(gateway.cookiesSecure)
                .sameSite("Lax")
                .path("/")
                .build(),
        )
    }

    /** Safe methods and non-API paths pass; everything else must echo the cookie in the header. */
    private fun isValid(request: ServerHttpRequest): Boolean {
        val path = request.path.value()
        if (path != "/api" && !path.startsWith("/api/")) {
            return true
        }
        if (request.method in setOf(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS)) {
            return true
        }
        val cookie = request.cookies.getFirst(Csrf.COOKIE_NAME)?.value
        val header = request.headers.getFirst(Csrf.HEADER_NAME)
        return !cookie.isNullOrEmpty() &&
            !header.isNullOrEmpty() &&
            MessageDigest.isEqual(cookie.toByteArray(), header.toByteArray())
    }
}
