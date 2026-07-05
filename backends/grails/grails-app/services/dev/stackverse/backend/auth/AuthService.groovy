package dev.stackverse.backend.auth

import dev.stackverse.backend.config.EventLogger
import dev.stackverse.backend.support.ApiError
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken

class AuthService {
    EventLogger eventLogger

    Map currentUser() {
        def authentication = SecurityContextHolder.context.authentication
        if (!(authentication instanceof JwtAuthenticationToken) || !authentication.authenticated) {
            return null
        }
        Map claims = authentication.token.claims
        [
            username: claims["preferred_username"] ?: authentication.name,
            name    : claims["name"],
            email   : claims["email"],
            roles   : roles(claims)
        ]
    }

    Map requireUser() {
        Map user = currentUser()
        if (!user?.username) {
            throw ApiError.unauthorized()
        }
        user
    }

    Map requireRole(String role) {
        Map user = requireUser()
        if (!user.roles.contains(role)) {
            eventLogger.info("authz_denied", "denied", "Denied a request lacking the required role", [actor: user.username])
            throw ApiError.forbidden()
        }
        user
    }

    private static List<String> roles(Map claims) {
        Map realmAccess = (claims["realm_access"] ?: [:]) as Map
        Collection raw = (realmAccess["roles"] ?: []) as Collection
        raw.findAll { it instanceof String && it in ["moderator", "admin"] }.collect { it as String }.sort()
    }
}
