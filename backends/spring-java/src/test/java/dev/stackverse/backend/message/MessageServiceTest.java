package dev.stackverse.backend.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.stackverse.backend.audit.AuditService;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class MessageServiceTest {
    private final MessageRepository repository = mock(MessageRepository.class);
    private final MessageService service = new MessageService(repository, mock(AuditService.class));

    @Test
    void defaultLanguageBundleUsesOneLanguage() {
        when(repository.findByLanguageIn(Set.of("en"))).thenReturn(List.of(message("ui.action.save", "en", "Save")));

        assertThat(service.bundle("en")).containsEntry("ui.action.save", "Save");
        verify(repository).findByLanguageIn(Set.of("en"));
    }

    @Test
    void bundleOverlaysRequestedLanguageAndFallsBackToEnglish() {
        Set<String> languages = Set.of("en", "pl");
        when(repository.findByLanguageIn(languages)).thenReturn(
            List.of(
                message("ui.action.save", "en", "Save"),
                message("ui.action.save", "pl", "Zapisz"),
                message("ui.nav.home", "en", "Home")
            )
        );

        assertThat(service.bundle("pl"))
            .containsEntry("ui.action.save", "Zapisz")
            .containsEntry("ui.nav.home", "Home");
    }

    private static Message message(String key, String language, String text) {
        return new Message(key, language, text, null, Instant.EPOCH, Instant.EPOCH);
    }
}
