package dev.stackverse.openliberty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.Map;

/** JSON request DTO for runtime-managed messages. */
public record MessageInput(
        @NotBlank(message = "{validation.message.key.invalid}") @Pattern(
                        regexp = "^[a-z0-9-]+(\\.[a-z0-9-]+)*$",
                        message = "{validation.message.key.invalid}")
                @Size(max = 150, message = "{validation.message.key.invalid}") String key,
        @NotBlank(message = "{validation.message.language.invalid}") @Pattern(regexp = "^[a-z]{2}$", message = "{validation.message.language.invalid}") String language,
        @NotEmpty(message = "{validation.message.text.required}") @Size(max = 2000, message = "{validation.message.text.too-long}") String text,
        @Size(max = 1000, message = "{validation.message.description.too-long}") String description) {

    public MessageInput {
        key = key == null ? "" : key.trim();
        language = language == null ? "" : language.trim();
        text = text == null ? "" : text;
    }

    Map<String, Object> toMap() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("key", key);
        result.put("language", language);
        result.put("text", text);
        if (description != null) {
            result.put("description", description);
        }
        return result;
    }
}
