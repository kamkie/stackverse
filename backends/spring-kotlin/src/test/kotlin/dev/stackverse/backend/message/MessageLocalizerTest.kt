package dev.stackverse.backend.message

import jakarta.servlet.http.HttpServletRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.`when`
import java.time.Instant

class MessageLocalizerTest {

    private val repository = mock(MessageRepository::class.java)
    private val languageResolver = mock(LanguageResolver::class.java)
    private val request = mock(HttpServletRequest::class.java)
    private val localizer = MessageLocalizer(repository, languageResolver)

    @Test
    fun `uses the text for the language resolved from the request`() {
        `when`(request.getParameter("lang")).thenReturn("pl")
        `when`(request.getHeader("Accept-Language")).thenReturn("en;q=0.8")
        `when`(languageResolver.resolve("pl", "en;q=0.8")).thenReturn("pl")
        `when`(repository.findByKeyAndLanguage("validation.title.required", "pl"))
            .thenReturn(message("pl", "Tytuł jest wymagany."))

        assertThat(localizer.localize("validation.title.required", request)).isEqualTo("Tytuł jest wymagany.")

        verify(repository).findByKeyAndLanguage("validation.title.required", "pl")
        verifyNoMoreInteractions(repository)
    }

    @Test
    fun `falls back to english when the resolved language lacks the key`() {
        `when`(languageResolver.resolve(null, "pl")).thenReturn("pl")
        `when`(request.getHeader("Accept-Language")).thenReturn("pl")
        `when`(repository.findByKeyAndLanguage("validation.url.invalid", "pl")).thenReturn(null)
        `when`(repository.findByKeyAndLanguage("validation.url.invalid", DEFAULT_LANGUAGE))
            .thenReturn(message(DEFAULT_LANGUAGE, "Enter a valid URL."))

        assertThat(localizer.localize("validation.url.invalid", request)).isEqualTo("Enter a valid URL.")
    }

    @Test
    fun `falls back to the stable key when no localized text exists`() {
        `when`(languageResolver.resolve(null, null)).thenReturn("pl")
        `when`(repository.findByKeyAndLanguage("validation.unknown", "pl")).thenReturn(null)
        `when`(repository.findByKeyAndLanguage("validation.unknown", DEFAULT_LANGUAGE)).thenReturn(null)

        assertThat(localizer.localize("validation.unknown", request)).isEqualTo("validation.unknown")

        verify(repository).findByKeyAndLanguage("validation.unknown", "pl")
        verify(repository).findByKeyAndLanguage("validation.unknown", DEFAULT_LANGUAGE)
        verifyNoMoreInteractions(repository)
    }

    private fun message(language: String, text: String) = Message(
        key = "validation.test",
        language = language,
        text = text,
        description = null,
        createdAt = Instant.EPOCH,
        updatedAt = Instant.EPOCH,
    )
}
