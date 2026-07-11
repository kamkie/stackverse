package dev.stackverse.gateway.config

import dev.stackverse.gateway.common.logEvent
import dev.stackverse.gateway.relay.TokenEndpointResponseStatusException
import dev.stackverse.gateway.web.Csrf
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.HttpMethod
import org.springframework.http.ReactiveHttpInputMessage
import org.springframework.http.client.reactive.ClientHttpResponse
import org.springframework.security.config.web.server.ServerHttpSecurity
import org.springframework.security.oauth2.client.ReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.RefreshTokenReactiveOAuth2AuthorizedClientProvider
import org.springframework.security.oauth2.client.endpoint.OAuth2RefreshTokenGrantRequest
import org.springframework.security.oauth2.client.endpoint.ReactiveOAuth2AccessTokenResponseClient
import org.springframework.security.oauth2.client.endpoint.WebClientReactiveRefreshTokenTokenResponseClient
import org.springframework.security.oauth2.client.registration.ClientRegistration
import org.springframework.security.oauth2.client.registration.ClientRegistrations
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.web.DefaultReactiveOAuth2AuthorizedClientManager
import org.springframework.security.oauth2.client.web.OAuth2AuthorizationRequestCustomizers
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.client.web.server.WebSessionServerOAuth2AuthorizedClientRepository
import org.springframework.security.oauth2.core.ClientAuthenticationMethod
import org.springframework.security.oauth2.core.OAuth2AuthorizationException
import org.springframework.security.oauth2.core.endpoint.OAuth2AccessTokenResponse
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest
import org.springframework.security.oauth2.core.web.reactive.function.OAuth2BodyExtractors
import org.springframework.security.web.server.SecurityWebFilterChain
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationFailureHandler
import org.springframework.security.web.server.authentication.RedirectServerAuthenticationSuccessHandler
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler
import org.springframework.security.web.server.csrf.CookieServerCsrfTokenRepository
import org.springframework.security.web.server.csrf.ServerCsrfTokenRepository
import org.springframework.security.web.server.savedrequest.NoOpServerRequestCache
import org.springframework.security.web.server.util.matcher.PathPatternParserServerWebExchangeMatcher
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers
import org.springframework.web.reactive.function.BodyExtractor
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.session.CookieWebSessionIdResolver
import org.springframework.web.server.session.WebSessionIdResolver
import reactor.core.publisher.Mono
import java.time.Duration

/**
 * The OIDC client side of the BFF: authorization code flow with PKCE against Keycloak,
 * mapped onto the contract's `/auth/login` and `/auth/callback` paths
 * (docs/ARCHITECTURE.md). Everything is `permitAll` — the gateway makes no
 * authorization decisions; the api route relays anonymously without a session and the
 * backend authorizes per endpoint.
 */
@Configuration
class SecurityConfig {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * The Keycloak client registration cannot be described with
     * `spring.security.oauth2.*` properties alone because the gateway's own calls
     * must dial [GatewayProperties.Oidc.internalIssuerUri] while issuer validation and
     * the browser-facing authorization redirect stay on the public issuer. Spring
     * Security still parses OIDC discovery and builds the [ClientRegistration]; this
     * bean only re-bases the back-channel endpoints the contract needs split.
     * Resolved at startup, so the IdP must be up when the gateway boots.
     */
    @Bean
    fun clientRegistrationRepository(gateway: GatewayProperties): ReactiveClientRegistrationRepository {
        val oidc = gateway.oidc
        val metadata: Map<String, Any> = WebClient.create()
            .get().uri("${oidc.internalIssuerUri}/.well-known/openid-configuration")
            .retrieve()
            .bodyToMono(object : ParameterizedTypeReference<Map<String, Any>>() {})
            .block(Duration.ofSeconds(30))
            ?: error("empty OIDC discovery document from ${oidc.internalIssuerUri}")

        val metadataIssuer = metadata["issuer"] as? String
            ?: error("OIDC discovery document carries no issuer")
        check(metadataIssuer == oidc.issuerUri) {
            "the IdP announces issuer $metadataIssuer but OIDC_ISSUER_URI is ${oidc.issuerUri}"
        }

        fun rebase(url: String): String =
            if (url.startsWith(oidc.issuerUri)) oidc.internalIssuerUri + url.removePrefix(oidc.issuerUri) else url

        val discovered = ClientRegistrations.fromOidcConfiguration(metadata)
            .registrationId("keycloak")
            .clientId(oidc.clientId)
            .clientSecret(oidc.clientSecret)
            .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
            // deterministic, built from PUBLIC_URL — never from the Host header
            .redirectUri(gateway.publicUrl.toString().trimEnd('/') + "/auth/callback")
            .scope("openid", "profile", "email")
            // the repo's identity claim (docs/LOGGING.md §4): authentication.name
            // becomes preferred_username everywhere — /auth/session, log actor
            .userNameAttributeName("preferred_username")
            .build()
        val rebasedMetadata = discovered.providerDetails.configurationMetadata.toMutableMap()
        for (endpoint in listOf("token_endpoint", "jwks_uri", "end_session_endpoint")) {
            (rebasedMetadata[endpoint] as? String)?.let { rebasedMetadata[endpoint] = rebase(it) }
        }
        require(rebasedMetadata["end_session_endpoint"] is String) {
            "OIDC discovery document carries no end_session_endpoint"
        }
        val registration = ClientRegistration.withClientRegistration(discovered)
            .tokenUri(rebase(discovered.providerDetails.tokenUri))
            .jwkSetUri(rebase(discovered.providerDetails.jwkSetUri ?: error("OIDC discovery document carries no jwks_uri")))
            .providerConfigurationMetadata(rebasedMetadata)
            .build()
        return InMemoryReactiveClientRegistrationRepository(registration)
    }

    /**
     * Tokens live in the WebSession — i.e. in Redis via Spring Session — so the
     * gateway process stays stateless: any instance can resolve any session.
     * (The default repository would fall back to an in-process client service.)
     */
    @Bean
    fun authorizedClientRepository(): ServerOAuth2AuthorizedClientRepository =
        WebSessionServerOAuth2AuthorizedClientRepository()

    @Bean
    fun refreshTokenResponseClient(): ReactiveOAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest> {
        val delegate = WebClientReactiveRefreshTokenTokenResponseClient().apply {
            setBodyExtractor(StatusPreservingOAuth2BodyExtractor())
        }
        return ReactiveOAuth2AccessTokenResponseClient { grantRequest ->
            delegate.getTokenResponse(grantRequest).timeout(IDP_TIMEOUT)
        }
    }

    @Bean
    fun authorizedClientManager(
        clientRegistrations: ReactiveClientRegistrationRepository,
        authorizedClients: ServerOAuth2AuthorizedClientRepository,
        refreshTokenResponseClient: ReactiveOAuth2AccessTokenResponseClient<OAuth2RefreshTokenGrantRequest>,
    ): ReactiveOAuth2AuthorizedClientManager {
        val refreshProvider = RefreshTokenReactiveOAuth2AuthorizedClientProvider().apply {
            setAccessTokenResponseClient(refreshTokenResponseClient)
            setClockSkew(ACCESS_TOKEN_EXPIRY_SKEW)
        }
        return DefaultReactiveOAuth2AuthorizedClientManager(clientRegistrations, authorizedClients).apply {
            setAuthorizedClientProvider(refreshProvider)
        }
    }

    @Bean
    fun csrfTokenRepository(gateway: GatewayProperties): ServerCsrfTokenRepository =
        CookieServerCsrfTokenRepository.withHttpOnlyFalse().apply {
            setCookieName(Csrf.COOKIE_NAME)
            setHeaderName(Csrf.HEADER_NAME)
            setCookiePath("/")
            setCookieCustomizer { cookie ->
                cookie.secure(gateway.cookiesSecure)
                cookie.sameSite("Lax")
                cookie.path("/")
            }
        }

    /** The contract cookie: `stackverse_session`, HttpOnly, SameSite=Lax, Secure outside local dev. */
    @Bean
    fun webSessionIdResolver(gateway: GatewayProperties): WebSessionIdResolver =
        CookieWebSessionIdResolver().apply {
            setCookieName("stackverse_session")
            addCookieInitializer { cookie ->
                cookie.httpOnly(true).sameSite("Lax").secure(gateway.cookiesSecure).path("/")
            }
        }

    @Bean
    fun securityFilterChain(
        http: ServerHttpSecurity,
        clientRegistrations: ReactiveClientRegistrationRepository,
        authorizedClients: ServerOAuth2AuthorizedClientRepository,
    ): SecurityWebFilterChain = http
        .authorizeExchange { it.anyExchange().permitAll() }
        // Not unprotected (CodeQL java/spring-disabled-csrf-protection fires here and
        // is suppressed inline): CSRF is enforced by CsrfWebFilter, the contract's
        // hand-rolled double-submit check — docs/ARCHITECTURE.md pins its cookie name,
        // header name, protected methods, and 403 problem document identically across
        // gateway stacks, a wire shape Spring's own CSRF machinery would need more
        // custom code than the filter itself to reproduce. Covered by the CSRF
        // integration tests and the e2e suite. Logout stays the plain POST
        // /auth/logout controller (the contract wants a 204, not a redirect dance).
        .csrf { it.disable() } // codeql[java/spring-disabled-csrf-protection]
        .logout { it.disable() }
        // Spring's default header writer would stamp Cache-Control onto proxied API
        // responses and break the backend's ETag exhibit. The gateway contract's
        // safe, scoped response headers are added by SecurityHeadersWebFilter.
        .headers { it.disable() }
        .requestCache { it.requestCache(NoOpServerRequestCache.getInstance()) }
        .oauth2Login { login ->
            login.authorizationRequestResolver(authorizationRequestResolver(clientRegistrations))
            // contract: the registered redirect URI is GET /auth/callback
            login.authenticationMatcher(PathPatternParserServerWebExchangeMatcher("/auth/callback"))
            login.authorizedClientRepository(authorizedClients)
            login.authenticationSuccessHandler(successHandler())
            login.authenticationFailureHandler(failureHandler())
        }
        .build()

    /**
     * `GET /auth/login` starts the code flow (instead of Spring's default
     * `/oauth2/authorization/{registrationId}` trigger path), with PKCE S256 forced:
     * Spring only auto-enables PKCE for public clients, this confidential client
     * sends it too — the realm requires S256, and all gateway stacks share the same
     * wire behavior.
     */
    private fun authorizationRequestResolver(
        clientRegistrations: ReactiveClientRegistrationRepository,
    ): ServerOAuth2AuthorizationRequestResolver {
        val delegate = DefaultServerOAuth2AuthorizationRequestResolver(clientRegistrations)
        delegate.setAuthorizationRequestCustomizer(OAuth2AuthorizationRequestCustomizers.withPkce())
        val loginMatcher = ServerWebExchangeMatchers.pathMatchers(HttpMethod.GET, "/auth/login")
        return object : ServerOAuth2AuthorizationRequestResolver {
            override fun resolve(exchange: ServerWebExchange): Mono<OAuth2AuthorizationRequest> =
                loginMatcher.matches(exchange)
                    .filter { it.isMatch }
                    .flatMap { resolve(exchange, "keycloak") }

            override fun resolve(exchange: ServerWebExchange, clientRegistrationId: String): Mono<OAuth2AuthorizationRequest> =
                delegate.resolve(exchange, clientRegistrationId)
        }
    }

    /**
     * Code flow finished: rotate the session id (the anonymous session that carried
     * the authorization request is now an authenticated one — fixation hygiene, and
     * the fresh cookie rides the callback's redirect), log the contract events, and
     * land the user back on the SPA.
     */
    private fun successHandler(): ServerAuthenticationSuccessHandler {
        val redirect = RedirectServerAuthenticationSuccessHandler("/")
        return ServerAuthenticationSuccessHandler { webFilterExchange, authentication ->
            log.logEvent(
                Level.INFO, "oidc_callback_completed", "success", "Authorization code flow completed",
                "actor" to authentication.name,
            )
            webFilterExchange.exchange.session
                .flatMap { session -> session.changeSessionId().thenReturn(session) }
                .doOnNext {
                    log.logEvent(
                        Level.INFO, "session_created", "success", "Session stored in Redis, cookie issued",
                        "actor" to authentication.name,
                    )
                }
                .then(redirect.onAuthenticationSuccess(webFilterExchange, authentication))
        }
    }

    /**
     * A failed callback is expected client/IdP behavior, not an application error
     * (docs/ARCHITECTURE.md): the user pressed Cancel on the Keycloak form, or the
     * state is stale or replayed. INFO with the failure *type* only — the message
     * can echo client-controlled query text (docs/LOGGING.md §6) — then back to the
     * SPA logged out, never a 5xx.
     */
    private fun failureHandler(): ServerAuthenticationFailureHandler {
        val redirect = RedirectServerAuthenticationFailureHandler("/")
        return ServerAuthenticationFailureHandler { webFilterExchange, exception ->
            log.logEvent(
                Level.INFO, "oidc_callback_completed", "failure", "Authorization code flow failed",
                "error_code" to exception.javaClass.simpleName,
            )
            redirect.onAuthenticationFailure(webFilterExchange, exception)
        }
    }

    private companion object {
        val ACCESS_TOKEN_EXPIRY_SKEW: Duration = Duration.ofSeconds(30)
        val IDP_TIMEOUT: Duration = Duration.ofSeconds(30)
    }
}

private class StatusPreservingOAuth2BodyExtractor(
    private val delegate: BodyExtractor<Mono<OAuth2AccessTokenResponse>, ReactiveHttpInputMessage> =
        OAuth2BodyExtractors.oauth2AccessTokenResponse(),
) : BodyExtractor<Mono<OAuth2AccessTokenResponse>, ReactiveHttpInputMessage> {
    override fun extract(
        inputMessage: ReactiveHttpInputMessage,
        context: BodyExtractor.Context,
    ): Mono<OAuth2AccessTokenResponse> {
        val statusCode = (inputMessage as? ClientHttpResponse)?.statusCode?.value()
        return delegate.extract(inputMessage, context)
            .onErrorMap(OAuth2AuthorizationException::class.java) { e ->
                if (statusCode == null) {
                    e
                } else {
                    OAuth2AuthorizationException(
                        e.error,
                        e.message ?: e.error.toString(),
                        TokenEndpointResponseStatusException(statusCode, e),
                    )
                }
            }
    }
}
