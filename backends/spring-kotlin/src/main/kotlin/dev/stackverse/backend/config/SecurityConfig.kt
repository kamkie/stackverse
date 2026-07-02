package dev.stackverse.backend.config

import dev.stackverse.backend.account.UserAccountFilter
import dev.stackverse.backend.account.UserAccountService
import dev.stackverse.backend.message.MessageLocalizer
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import tools.jackson.databind.ObjectMapper

/**
 * Stateless resource server: every request stands on its own bearer JWT, validated
 * against the IdP's JWKS (issuer + audience checked via configuration properties).
 * Role checks live on the controllers as `@PreAuthorize` — each endpoint asks for
 * the single role it needs; the admin ⊃ moderator hierarchy is Keycloak's composite
 * role, never re-implemented here.
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        accountService: UserAccountService,
        localizer: MessageLocalizer,
        objectMapper: ObjectMapper,
    ): SecurityFilterChain {
        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it
                    .requestMatchers("/healthz", "/readyz").permitAll()
                    // public surface (SPEC rule 2 + 7); the listing controllers still
                    // demand authentication unless visibility=public is requested
                    .requestMatchers(HttpMethod.GET, "/api/v1/bookmarks", "/api/v2/bookmarks", "/api/v1/bookmarks/{id}").permitAll()
                    .requestMatchers(HttpMethod.GET, "/api/v1/messages", "/api/v1/messages/**").permitAll()
                    .anyRequest().authenticated()
            }
            .oauth2ResourceServer { resourceServer ->
                resourceServer.jwt { it.jwtAuthenticationConverter(jwtAuthenticationConverter()) }
                resourceServer.authenticationEntryPoint { _, response, _ ->
                    writeProblem(response, objectMapper, HttpStatus.UNAUTHORIZED, "Missing or invalid bearer token.")
                }
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    writeProblem(response, objectMapper, HttpStatus.UNAUTHORIZED, "Authentication is required.")
                }
                it.accessDeniedHandler { _, response, _ ->
                    writeProblem(response, objectMapper, HttpStatus.FORBIDDEN, "You do not have the role required for this operation.")
                }
            }
            .addFilterAfter(
                UserAccountFilter(accountService, localizer, objectMapper),
                BearerTokenAuthenticationFilter::class.java,
            )
        return http.build()
    }

    /** Identity = `preferred_username`; authorities = `realm_access.roles` (SPEC rule 6). */
    private fun jwtAuthenticationConverter(): JwtAuthenticationConverter {
        val converter = JwtAuthenticationConverter()
        converter.setPrincipalClaimName("preferred_username")
        converter.setJwtGrantedAuthoritiesConverter { jwt: Jwt ->
            val realmAccess = jwt.getClaimAsMap("realm_access").orEmpty()
            (realmAccess["roles"] as? Collection<*>).orEmpty()
                .filterIsInstance<String>()
                .map { SimpleGrantedAuthority("ROLE_$it") }
        }
        return converter
    }

    private fun writeProblem(response: HttpServletResponse, objectMapper: ObjectMapper, status: HttpStatus, detail: String) {
        response.status = status.value()
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        objectMapper.writeValue(
            response.outputStream,
            mapOf(
                "type" to "about:blank",
                "title" to status.reasonPhrase,
                "status" to status.value(),
                "detail" to detail,
            ),
        )
    }
}
