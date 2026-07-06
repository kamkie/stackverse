package dev.stackverse.backend.message;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties("stackverse.seed")
public record SeedProperties(String messagesDir) {
}
