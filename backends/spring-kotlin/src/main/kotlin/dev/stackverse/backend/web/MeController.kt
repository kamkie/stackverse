package dev.stackverse.backend.web

import com.fasterxml.jackson.annotation.JsonInclude
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** The two application roles; everything else in `realm_access.roles` is Keycloak plumbing. */
private val APP_ROLES = setOf("moderator", "admin")

@JsonInclude(JsonInclude.Include.NON_NULL)
data class UserResponse(
    val username: String,
    val name: String?,
    val email: String?,
    val roles: List<String>,
)

@RestController
class MeController {

    @GetMapping("/api/v1/me")
    fun me(authentication: JwtAuthenticationToken): UserResponse = UserResponse(
        username = authentication.name,
        name = authentication.token.getClaimAsString("name"),
        email = authentication.token.getClaimAsString("email"),
        roles = authentication.authorities
            .mapNotNull { it.authority?.removePrefix("ROLE_") }
            .filter { it in APP_ROLES }
            .sorted(),
    )
}
