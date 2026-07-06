package dev.stackverse.backend.message;

public record MessageRequest(String key, String language, String text, String description) {
}
