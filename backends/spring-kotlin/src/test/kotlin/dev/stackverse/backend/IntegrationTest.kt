package dev.stackverse.backend

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import org.testcontainers.containers.PostgreSQLContainer

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

    @Bean
    @ServiceConnection
    fun postgres(): PostgreSQLContainer<*> = PostgreSQLContainer("postgres:17-alpine")
}

/**
 * Full-stack tests against a Testcontainers PostgreSQL. Authentication is a
 * pre-built [JwtAuthenticationToken] — shaped exactly like what the resource
 * server derives from a Keycloak token — injected into the security context,
 * so no IdP is needed.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class)
abstract class IntegrationTest {

    @Autowired
    lateinit var mockMvc: MockMvc

    /** A regular user; add "moderator" or "moderator", "admin" for backoffice roles. */
    fun user(username: String, vararg roles: String): RequestPostProcessor {
        val jwt = Jwt.withTokenValue("test-token")
            .header("alg", "none")
            .subject("subject-of-$username")
            .claim("preferred_username", username)
            .claim("realm_access", mapOf("roles" to roles.toList() + "default-roles-stackverse"))
            .claim("name", "${username.replaceFirstChar { it.uppercase() }} User")
            .claim("email", "$username@stackverse.local")
            .build()
        val authorities = (roles.toList() + "default-roles-stackverse").map { SimpleGrantedAuthority("ROLE_$it") }
        return authentication(JwtAuthenticationToken(jwt, authorities, username))
    }

    fun moderator(username: String = "moderator") = user(username, "moderator")

    /** Keycloak's `admin` is a composite including `moderator`, so admin tokens carry both. */
    fun admin(username: String = "admin") = user(username, "moderator", "admin")
}
