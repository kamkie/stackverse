package dev.stackverse.backend.config;

import dev.stackverse.backend.account.UserAccountFilter;
import dev.stackverse.backend.account.UserAccountService;
import dev.stackverse.backend.common.Logging;
import dev.stackverse.backend.message.MessageLocalizer;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Collection;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.SupplierJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.oauth2.server.resource.web.authentication.BearerTokenAuthenticationFilter;
import org.springframework.security.web.SecurityFilterChain;
import tools.jackson.databind.ObjectMapper;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
public class SecurityConfig {
    private final org.slf4j.Logger log = LoggerFactory.getLogger(getClass());

    @Bean
    SecurityFilterChain securityFilterChain(
        HttpSecurity http,
        UserAccountService accountService,
        MessageLocalizer localizer,
        ObjectMapper objectMapper
    ) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .requestMatchers("/healthz", "/readyz").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/bookmarks", "/api/v2/bookmarks", "/api/v1/bookmarks/{id}").permitAll()
                .requestMatchers(HttpMethod.GET, "/api/v1/messages", "/api/v1/messages/**").permitAll()
                .anyRequest().authenticated()
            )
            .oauth2ResourceServer(resourceServer -> {
                resourceServer.jwt(jwt -> jwt.jwtAuthenticationConverter(jwtAuthenticationConverter()));
                resourceServer.authenticationEntryPoint((request, response, exception) -> {
                    String errorCode = exception instanceof OAuth2AuthenticationException oauth
                        ? oauth.getError().getErrorCode()
                        : "invalid_token";
                    Logging.logEvent(
                        log,
                        Level.INFO,
                        "jwt_validation_failed",
                        "failure",
                        "Rejected a bearer token",
                        "error_code", errorCode
                    );
                    writeProblem(response, objectMapper, HttpStatus.UNAUTHORIZED, "Missing or invalid bearer token.");
                });
            })
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) ->
                    writeProblem(response, objectMapper, HttpStatus.UNAUTHORIZED, "Authentication is required.")
                )
                .accessDeniedHandler((request, response, exception) -> {
                    var authentication = SecurityContextHolder.getContext().getAuthentication();
                    Logging.logEvent(
                        log,
                        Level.INFO,
                        "authz_denied",
                        "denied",
                        "Denied a request lacking the required role",
                        "actor", authentication == null ? null : authentication.getName()
                    );
                    writeProblem(response, objectMapper, HttpStatus.FORBIDDEN, "You do not have the role required for this operation.");
                })
            )
            .addFilterAfter(new UserAccountFilter(accountService, localizer, objectMapper), BearerTokenAuthenticationFilter.class);
        return http.build();
    }

    /**
     * Built lazily on first request so the service can start while the IdP is still booting.
     */
    @Bean
    JwtDecoder jwtDecoder(OidcProperties properties) {
        return new SupplierJwtDecoder(() -> {
            NimbusJwtDecoder decoder = properties.jwksUri() == null || properties.jwksUri().isBlank()
                ? NimbusJwtDecoder.withIssuerLocation(properties.issuerUri()).build()
                : NimbusJwtDecoder.withJwkSetUri(properties.jwksUri()).build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                JwtValidators.createDefaultWithIssuer(properties.issuerUri()),
                audienceValidator(properties.audience())
            ));
            return decoder;
        });
    }

    private OAuth2TokenValidator<Jwt> audienceValidator(String audience) {
        return jwt -> jwt.getAudience().contains(audience)
            ? OAuth2TokenValidatorResult.success()
            : OAuth2TokenValidatorResult.failure(
                new OAuth2Error("invalid_token", "The token is missing the required audience '" + audience + "'.", null)
            );
    }

    /** Identity = preferred_username; authorities = realm_access.roles. */
    private JwtAuthenticationConverter jwtAuthenticationConverter() {
        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
        converter.setPrincipalClaimName("preferred_username");
        converter.setJwtGrantedAuthoritiesConverter(jwt -> {
            Map<String, Object> realmAccess = jwt.getClaimAsMap("realm_access");
            Object roles = realmAccess == null ? null : realmAccess.get("roles");
            if (!(roles instanceof Collection<?> collection)) {
                return java.util.List.of();
            }
            return collection.stream()
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(role -> (GrantedAuthority) new SimpleGrantedAuthority("ROLE_" + role))
                .toList();
        });
        return converter;
    }

    private void writeProblem(HttpServletResponse response, ObjectMapper objectMapper, HttpStatus status, String detail) throws java.io.IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        objectMapper.writeValue(
            response.getOutputStream(),
            Map.of(
                "type", "about:blank",
                "title", status.getReasonPhrase(),
                "status", status.value(),
                "detail", detail
            )
        );
    }
}
