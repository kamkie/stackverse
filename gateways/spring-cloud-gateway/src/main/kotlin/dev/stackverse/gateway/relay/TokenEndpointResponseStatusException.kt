package dev.stackverse.gateway.relay

/**
 * Carries the token endpoint's HTTP verdict through Spring Security's OAuth2
 * body parsing so refresh policy can keep the Stackverse 400/401 contract.
 */
class TokenEndpointResponseStatusException(
    val statusCode: Int,
    cause: Throwable,
) : RuntimeException("Token endpoint returned HTTP $statusCode", cause)
