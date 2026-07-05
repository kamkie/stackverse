package dev.stackverse.backend.config

import dev.stackverse.backend.support.ApiError
import dev.stackverse.backend.support.JsonSupport
import dev.stackverse.backend.account.UserAccountFilter
import dev.stackverse.backend.account.UserAccountService
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpMethod
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator
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

@ConfigurationProperties("stackverse.oidc")
class OidcProperties {
    String issuerUri
    String jwksUri
    String audience
}

@Configuration
@EnableWebSecurity
@EnableConfigurationProperties(OidcProperties)
class SecurityConfig {
    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http, UserAccountService accountService, EventLogger eventLogger) {
        JwtAuthenticationConverter converter = jwtAuthenticationConverter()

        http
            .csrf { it.disable() }
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .authorizeHttpRequests {
                it.requestMatchers("/healthz", "/readyz").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/bookmarks", "/api/v2/bookmarks", "/api/v1/bookmarks/*").permitAll()
                it.requestMatchers(HttpMethod.GET, "/api/v1/messages", "/api/v1/messages/**").permitAll()
                it.anyRequest().permitAll()
            }
            .oauth2ResourceServer { resourceServer ->
                resourceServer.jwt { it.jwtAuthenticationConverter(converter) }
                resourceServer.authenticationEntryPoint { request, response, exception ->
                    eventLogger.info("jwt_validation_failed", "failure", "Rejected a bearer token", [error_code: "invalid_token"])
                    JsonSupport.writeProblem(response, ApiError.unauthorized("Missing or invalid bearer token."))
                }
            }
            .exceptionHandling {
                it.authenticationEntryPoint { request, response, exception ->
                    JsonSupport.writeProblem(response, ApiError.unauthorized())
                }
                it.accessDeniedHandler { request, response, exception ->
                    JsonSupport.writeProblem(response, ApiError.forbidden())
                }
            }
            .addFilterAfter(new UserAccountFilter(accountService, eventLogger), BearerTokenAuthenticationFilter)
        http.build()
    }

    @Bean
    JwtDecoder jwtDecoder(OidcProperties properties) {
        OAuth2TokenValidator<Jwt> audience = audienceValidator(properties.audience)

        new SupplierJwtDecoder({
            NimbusJwtDecoder decoder = properties.jwksUri ?
                NimbusJwtDecoder.withJwkSetUri(properties.jwksUri).build() :
                NimbusJwtDecoder.withIssuerLocation(properties.issuerUri).build()
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<Jwt>(
                JwtValidators.createDefaultWithIssuer(properties.issuerUri),
                audience
            ))
            decoder
        })
    }

    private static OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        { Jwt jwt ->
            jwt.audience?.contains(audience) ?
                OAuth2TokenValidatorResult.success() :
                OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "The token is missing the required audience '${audience}'.", null))
        } as OAuth2TokenValidator<Jwt>
    }

    private static JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter()
        converter.setPrincipalClaimName("preferred_username")
        converter.setJwtGrantedAuthoritiesConverter { Jwt jwt ->
            Map realmAccess = (jwt.claims["realm_access"] ?: [:]) as Map
            Collection roles = (realmAccess["roles"] ?: []) as Collection
            roles.findAll { it instanceof String }.collect { new SimpleGrantedAuthority("ROLE_${it}") }
        }
        converter
    }
}
