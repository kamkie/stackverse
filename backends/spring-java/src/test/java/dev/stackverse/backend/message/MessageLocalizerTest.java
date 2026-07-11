package dev.stackverse.backend.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;

class MessageLocalizerTest {
    private final MessageRepository repository = mock(MessageRepository.class);
    private final LanguageResolver languageResolver = mock(LanguageResolver.class);
    private final MessageLocalizer localizer = new MessageLocalizer(repository, languageResolver);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @Test
    void returnsMessageInResolvedLanguage() {
        when(request.getParameter("lang")).thenReturn("pl");
        when(request.getHeader(HttpHeaders.ACCEPT_LANGUAGE)).thenReturn("en;q=0.8");
        when(languageResolver.resolve("pl", "en;q=0.8")).thenReturn("pl");
        when(repository.findByKeyAndLanguage("validation.title.required", "pl"))
            .thenReturn(message("validation.title.required", "pl", "Podaj tytul."));

        assertThat(localizer.localize("validation.title.required", request)).isEqualTo("Podaj tytul.");

        verify(languageResolver).resolve("pl", "en;q=0.8");
    }

    @Test
    void fallsBackToEnglishWhenResolvedLanguageLacksKey() {
        when(languageResolver.resolve(null, null)).thenReturn("pl");
        when(repository.findByKeyAndLanguage("validation.url.invalid", "pl")).thenReturn(null);
        when(repository.findByKeyAndLanguage("validation.url.invalid", "en"))
            .thenReturn(message("validation.url.invalid", "en", "Enter a valid URL."));

        assertThat(localizer.localize("validation.url.invalid", request)).isEqualTo("Enter a valid URL.");
    }

    @Test
    void fallsBackToKeyWhenNoMessageExists() {
        when(languageResolver.resolve(null, null)).thenReturn("en");
        when(repository.findByKeyAndLanguage("validation.missing", "en")).thenReturn(null);

        assertThat(localizer.localize("validation.missing", request)).isEqualTo("validation.missing");
    }

    private static Message message(String key, String language, String text) {
        return new Message(key, language, text, null, Instant.EPOCH, Instant.EPOCH);
    }
}
