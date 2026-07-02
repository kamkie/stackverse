package dev.stackverse.backend.config

import dev.stackverse.backend.account.UserAccountFilter
import dev.stackverse.backend.account.UserAccountService
import dev.stackverse.backend.common.logEvent
import dev.stackverse.backend.message.MessageLocalizer
import jakarta.servlet.http.HttpServletResponse
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.boot.context.properties.ConfigurationProperties
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
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2AuthenticationException
import org.springframework.security.oauth2.core.OAuth2Error
import org.springframework.security.oauth2.core.OAuth2TokenValidator
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import org.springframework.security.oauth2.jwt.JwtValidators
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter
import org.springframework.security.web.SecurityFilterChain
import tools.jackson.databind.ObjectMapper

@ConfigurationProperties("stackverse.oidc")
data class OidcProperties(
    /** Expected `iss` claim; also the OIDC discovery endpoint when no JWKS URI is given. */
    val issuerUri: String,
    /** Where to fetch signing keys when the issuer host is not directly dialable (compose). */
    val jwksUri: String?,
    val audience: String,
)

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

    private val log = LoggerFactory.getLogger(javaClass)

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
                // fires only when a bearer token was presented and rejected — an expected
                // 401 and a security signal, never above INFO (docs/LOGGING.md §3)
                resourceServer.authenticationEntryPoint { _, response, exception ->
                    log.logEvent(
                        Level.INFO, "jwt_validation_failed", "failure", "Rejected a bearer token",
                        "error_code" to ((exception as? OAuth2AuthenticationException)?.error?.errorCode ?: "invalid_token"),
                    )
                    writeProblem(response, objectMapper, HttpStatus.UNAUTHORIZED, "Missing or invalid bearer token.")
                }
            }
            .exceptionHandling {
                it.authenticationEntryPoint { _, response, _ ->
                    writeProblem(response, objectMapper, HttpStatus.UNAUTHORIZED, "Authentication is required.")
                }
                it.accessDeniedHandler { _, response, _ ->
                    log.logEvent(
                        Level.INFO, "authz_denied", "denied", "Denied a request lacking the required role",
                        "actor" to SecurityContextHolder.getContext().authentication?.name,
                    )
                    writeProblem(response, objectMapper, HttpStatus.FORBIDDEN, "You do not have the role required for this operation.")
                }
            }
            .addFilterAfter(
                UserAccountFilter(accountService, localizer, objectMapper),
                BearerTokenAuthenticationFilter::class.java,
            )
        return http.build()
    }

    /**
     * Built lazily (first request, not startup) so the service comes up even while the
     * IdP is still booting. Signature keys come from the issuer's OIDC discovery, or
     * straight from `OIDC_JWKS_URI` when set; `iss` and `aud` are validated either way.
     */
    @Bean
    fun jwtDecoder(properties: OidcProperties): JwtDecoder = SupplierJwtDecoder {
        val decoder = if (properties.jwksUri.isNullOrBlank()) {
            NimbusJwtDecoder.withIssuerLocation(properties.issuerUri).build()
        } else {
            NimbusJwtDecoder.withJwkSetUri(properties.jwksUri).build()
        }
        decoder.setJwtValidator(
            DelegatingOAuth2TokenValidator(
                JwtValidators.createDefaultWithIssuer(properties.issuerUri),
                audienceValidator(properties.audience),
            ),
        )
        decoder
    }

    private fun audienceValidator(audience: String) = OAuth2TokenValidator<Jwt> { jwt ->
        if (audience in jwt.audience.orEmpty()) {
            OAuth2TokenValidatorResult.success()
        } else {
            OAuth2TokenValidatorResult.failure(
                OAuth2Error("invalid_token", "The token is missing the required audience '$audience'.", null),
            )
        }
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
