package dev.stackverse.backend.account

import dev.stackverse.backend.config.EventLogger
import groovy.json.JsonSlurper
import jakarta.servlet.FilterChain
import org.springframework.mock.web.MockHttpServletRequest
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import spock.lang.Specification

import java.time.Instant

class UserAccountFilterSpec extends Specification {
    UserAccountService accounts = Mock()
    EventLogger eventLogger = Mock()
    UserAccountFilter filter = new UserAccountFilter(accounts, eventLogger)

    def cleanup() {
        SecurityContextHolder.clearContext()
    }

    void 'anonymous requests pass through without provisioning an account'() {
        given:
        FilterChain chain = Mock()
        MockHttpServletRequest request = new MockHttpServletRequest('GET', '/api/v1/messages/bundle')
        MockHttpServletResponse response = new MockHttpServletResponse()

        when:
        filter.doFilter(request, response, chain)

        then:
        1 * chain.doFilter(request, response)
        0 * accounts._
        0 * eventLogger._
    }

    void 'active callers are lazily provisioned before the request continues'() {
        given:
        authenticate('demo')
        FilterChain chain = Mock()
        MockHttpServletRequest request = new MockHttpServletRequest('GET', '/api/v1/me')
        MockHttpServletResponse response = new MockHttpServletResponse()

        when:
        filter.doFilter(request, response, chain)

        then:
        1 * accounts.touch('demo') >> [username: 'demo', status: 'active']
        1 * chain.doFilter(request, response)
        0 * eventLogger._
    }

    void 'blocked callers receive a privacy-safe problem and never reach the controller'() {
        given:
        authenticate('blocked-user')
        FilterChain chain = Mock()
        MockHttpServletRequest request = new MockHttpServletRequest('POST', '/api/v1/bookmarks')
        request.addHeader('Authorization', 'Bearer secret-token-value')
        MockHttpServletResponse response = new MockHttpServletResponse()

        when:
        filter.doFilter(request, response, chain)

        then:
        1 * accounts.touch('blocked-user') >> [username: 'blocked-user', status: 'blocked']
        1 * eventLogger.warn(
            'blocked_user_rejected',
            'denied',
            'Blocked account rejected',
            { Map values -> values == [actor: 'blocked-user'] }
        )
        0 * chain.doFilter(_, _)
        response.status == 403
        response.contentType.startsWith('application/problem+json')
        new JsonSlurper().parseText(response.contentAsString) == [
            type  : 'about:blank',
            title : 'Forbidden',
            status: 403,
            detail: 'Your account has been blocked.'
        ]
        !response.contentAsString.contains('secret-token-value')
    }

    private static void authenticate(String username) {
        Instant now = Instant.parse('2026-07-11T12:00:00Z')
        Jwt jwt = Jwt.withTokenValue('test-token')
            .header('alg', 'none')
            .subject("subject-of-${username}")
            .issuedAt(now)
            .expiresAt(now.plusSeconds(300))
            .claim('preferred_username', username)
            .build()
        SecurityContextHolder.context.authentication = new JwtAuthenticationToken(jwt, [], username)
    }
}
