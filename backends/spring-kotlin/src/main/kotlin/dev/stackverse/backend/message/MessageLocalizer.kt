package dev.stackverse.backend.message

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component

/**
 * English texts for validation keys this implementation uses beyond the seeded set
 * (`spec/messages`). The messages table always wins — an admin creating one of these
 * keys at runtime overrides the built-in text; this catalog only prevents a raw key
 * from leaking into the `message` field when the table has nothing.
 */
private val BUILT_IN_TEXTS = mapOf(
    "validation.resolution.invalid" to "Resolution must be one of: dismissed, actioned.",
    "validation.resolution.note.too-long" to "Resolution note must be at most 1000 characters.",
    "validation.bookmark-status.invalid" to "Status must be one of: active, hidden.",
    "validation.bookmark-status.note.too-long" to "Note must be at most 1000 characters.",
    "validation.message.text.too-long" to "Message text must be at most 2000 characters.",
    "validation.message.description.too-long" to "Description must be at most 1000 characters.",
    "validation.block.reason.too-long" to "Block reason must be at most 1000 characters.",
)

/**
 * Resolves a message key to localized text for the current request (SPEC rule 11):
 * language per rule 8, text from the messages table, `en` fallback, then the
 * built-in catalog, and finally the key itself if no text exists at all.
 */
@Component
class MessageLocalizer(
    private val messageRepository: MessageRepository,
    private val languageResolver: LanguageResolver,
) {

    fun localize(key: String, request: HttpServletRequest): String {
        val language = languageResolver.resolve(
            request.getParameter("lang"),
            request.getHeader(HttpHeaders.ACCEPT_LANGUAGE),
        )
        return messageRepository.findByKeyAndLanguage(key, language)?.text
            ?: messageRepository.findByKeyAndLanguage(key, DEFAULT_LANGUAGE)?.text
            ?: BUILT_IN_TEXTS[key]
            ?: key
    }
}
