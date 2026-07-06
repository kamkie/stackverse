package dev.stackverse.backend.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stackverse.oidc")
public record OidcProperties(
    String issuerUri,
    String jwksUri,
    String audience
) {
}
