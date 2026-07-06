package dev.stackverse.backend.message;

import java.util.Map;

public record MessageBundleResponse(String language, Map<String, String> messages) {
}
