package dev.stackverse.backend.auth

import dev.stackverse.backend.config.EventLogger
import dev.stackverse.backend.support.ApiError
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import spock.lang.Specification

import java.time.Instant

class AuthServiceSpec extends Specification {
    AuthService service = new AuthService(eventLogger: Mock(EventLogger))

    def cleanup() {
        SecurityContextHolder.clearContext()
    }

    def "currentUser derives identity and app roles from JWT claims"() {
        given:
        SecurityContextHolder.context.authentication = authenticatedJwt([
            preferred_username: "demo",
            name              : "Demo User",
            email             : "demo@example.test",
            realm_access      : [roles: ["offline_access", "admin", "moderator", 42]]
        ])

        expect:
        service.currentUser() == [
            username: "demo",
            name    : "Demo User",
            email   : "demo@example.test",
            roles   : ["admin", "moderator"]
        ]
    }

    def "currentUser falls back to authentication name when preferred username is absent"() {
        given:
        SecurityContextHolder.context.authentication = authenticatedJwt([
            name        : "Fallback User",
            realm_access: [roles: []]
        ], "jwt-subject")

        expect:
        service.currentUser().username == "jwt-subject"
    }

    def "requireUser rejects missing authentication with a 401 problem"() {
        when:
        service.requireUser()

        then:
        ApiError error = thrown()
        error.status == 401
        error.title == "Unauthorized"
    }

    def "requireRole logs and rejects authenticated callers without the required role"() {
        given:
        SecurityContextHolder.context.authentication = authenticatedJwt([
            preferred_username: "demo",
            realm_access      : [roles: ["moderator"]]
        ])

        when:
        service.requireRole("admin")

        then:
        ApiError error = thrown()
        error.status == 403
        1 * service.eventLogger.info(
            "authz_denied",
            "denied",
            "Denied a request lacking the required role",
            { Map values -> values.actor == "demo" }
        )
    }

    def "requireRole returns the current user when the required role is present"() {
        given:
        SecurityContextHolder.context.authentication = authenticatedJwt([
            preferred_username: "admin",
            realm_access      : [roles: ["admin"]]
        ])

        expect:
        service.requireRole("admin").username == "admin"
    }

    private static JwtAuthenticationToken authenticatedJwt(Map<String, Object> claims, String principalName = null) {
        Jwt jwt = new Jwt(
            "token",
            Instant.parse("2026-07-05T10:00:00Z"),
            Instant.parse("2026-07-05T11:00:00Z"),
            [alg: "none"],
            claims
        )
        new JwtAuthenticationToken(jwt, [], principalName ?: claims.preferred_username ?: "subject")
    }
}
