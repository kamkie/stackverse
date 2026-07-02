package dev.stackverse.backend.message

import com.fasterxml.jackson.annotation.JsonInclude
import java.time.Instant
import java.util.UUID

data class MessageRequest(
    val key: String? = null,
    val language: String? = null,
    val text: String? = null,
    val description: String? = null,
)

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MessageResponse(
    val id: UUID,
    val key: String,
    val language: String,
    val text: String,
    val description: String?,
    val createdAt: Instant,
    val updatedAt: Instant,
) {
    companion object {
        fun of(message: Message) = MessageResponse(
            id = message.id,
            key = message.key,
            language = message.language,
            text = message.text,
            description = message.description,
            createdAt = message.createdAt,
            updatedAt = message.updatedAt,
        )
    }
}

data class MessageBundleResponse(
    val language: String,
    val messages: Map<String, String>,
)
