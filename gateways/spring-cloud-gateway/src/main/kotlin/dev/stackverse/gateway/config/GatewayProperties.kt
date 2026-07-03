package dev.stackverse.gateway.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.net.URI

/**
 * Environment-driven configuration — one property per variable in gateways/README.md
 * (`application.yaml` binds the env vars; `SPA_ROOT` is consumed by
 * [GatewayEnvironmentPostProcessor] as the fallback static-resource location).
 */
@ConfigurationProperties("stackverse")
class GatewayProperties(
    val backendUrl: URI,
    frontendUrl: String?,
    val publicUrl: URI,
    val oidc: Oidc,
) {
    /** Unset in the environment arrives as an empty string — treat it as absent. */
    val frontendUrl: URI? = frontendUrl?.takeIf { it.isNotBlank() }?.let(URI::create)

    /** The contract: cookies are Secure outside local dev, i.e. whenever the public URL is https. */
    val cookiesSecure: Boolean = publicUrl.scheme == "https"

    class Oidc(
        issuerUri: String,
        internalIssuerUri: String?,
        val clientId: String,
        val clientSecret: String,
    ) {
        val issuerUri: String = issuerUri.trimEnd('/')

        /**
         * Base URL for the gateway's own calls to the IdP — discovery, token
         * exchange and refresh, JWKS, RP-initiated logout. Falls back to the public
         * issuer; compose sets it to the keycloak service, whose public hostname
         * (localhost:8180) is not dialable from inside the network. Issuer
         * validation and the browser-facing authorization redirect always use
         * [issuerUri] — this mirrors the backend's OIDC_JWKS_URI escape hatch.
         */
        val internalIssuerUri: String =
            internalIssuerUri?.takeIf { it.isNotBlank() }?.trimEnd('/') ?: this.issuerUri
    }
}
