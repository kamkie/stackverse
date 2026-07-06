package dev.stackverse.backend.message;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record MessageResponse(
    UUID id,
    String key,
    String language,
    String text,
    String description,
    Instant createdAt,
    Instant updatedAt
) {
    public static MessageResponse of(Message message) {
        return new MessageResponse(
            message.getId(),
            message.getKey(),
            message.getLanguage(),
            message.getText(),
            message.getDescription(),
            message.getCreatedAt(),
            message.getUpdatedAt()
        );
    }
}
