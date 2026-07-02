package dev.stackverse.backend.message

import org.springframework.stereotype.Component
import java.util.Locale

const val DEFAULT_LANGUAGE = "en"

/**
 * SPEC rule 8: explicit `lang` parameter → first supported language in `Accept-Language`
 * (quality-ordered) → `en`. Unsupported values fall back down the chain, never error.
 * "Supported" means at least one message exists in that language.
 */
@Component
class LanguageResolver(private val messageRepository: MessageRepository) {

    fun resolve(lang: String?, acceptLanguage: String?): String {
        val supported = messageRepository.findDistinctLanguages()
        if (lang != null && lang in supported) {
            return lang
        }
        if (!acceptLanguage.isNullOrBlank()) {
            val ranges = runCatching { Locale.LanguageRange.parse(acceptLanguage) }.getOrDefault(emptyList())
            for (range in ranges) {
                val code = Locale.forLanguageTag(range.range).language
                if (code in supported) {
                    return code
                }
            }
        }
        return DEFAULT_LANGUAGE
    }
}
