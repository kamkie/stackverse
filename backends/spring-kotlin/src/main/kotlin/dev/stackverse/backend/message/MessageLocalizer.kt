package dev.stackverse.backend.message

import jakarta.servlet.http.HttpServletRequest
import org.springframework.http.HttpHeaders
import org.springframework.stereotype.Component

/**
 * Resolves a message key to localized text for the current request (SPEC rule 11):
 * language per rule 8, text from the messages table, `en` fallback, and finally
 * the key itself if no text exists at all.
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
            ?: key
    }
}
