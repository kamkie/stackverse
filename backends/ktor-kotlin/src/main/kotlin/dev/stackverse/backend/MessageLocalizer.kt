package dev.stackverse.backend

import io.ktor.http.HttpHeaders
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.header
import java.util.Locale

class MessageLocalizer(private val messages: MessageRepository) {
    suspend fun resolve(lang: String?, acceptLanguage: String?): String {
        val supported = messages.supportedLanguages()
        if (lang != null && lang in supported) return lang
        parseAcceptLanguage(acceptLanguage).forEach { language ->
            if (language in supported) return language
        }
        return DEFAULT_LANGUAGE
    }

    suspend fun localize(key: String, call: ApplicationCall): String {
        val language = resolve(call.request.queryParameters["lang"], call.request.headers[HttpHeaders.AcceptLanguage])
        return messages.text(key, language) ?: messages.text(key, DEFAULT_LANGUAGE) ?: key
    }

    suspend fun localizeAll(keys: List<String>, call: ApplicationCall): Map<String, String> {
        val uniqueKeys = keys.toSet()
        val language = resolve(call.request.queryParameters["lang"], call.request.headers[HttpHeaders.AcceptLanguage])
        val languages = if (language == DEFAULT_LANGUAGE) setOf(DEFAULT_LANGUAGE) else setOf(language, DEFAULT_LANGUAGE)
        val texts = messages.texts(uniqueKeys, languages)
        return uniqueKeys.associateWith { key ->
            texts[key to language] ?: texts[key to DEFAULT_LANGUAGE] ?: key
        }
    }

    private fun parseAcceptLanguage(header: String?): List<String> {
        if (header.isNullOrBlank()) return emptyList()
        return runCatching {
            Locale.LanguageRange.parse(header).mapNotNull { range ->
                Locale.forLanguageTag(range.range).language.takeIf { it.isNotBlank() }
            }
        }.getOrDefault(emptyList())
    }
}
