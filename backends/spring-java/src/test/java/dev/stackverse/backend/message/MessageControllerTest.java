package dev.stackverse.backend.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.stackverse.backend.common.NotFoundProblem;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpHeaders;
import org.springframework.security.core.Authentication;

class MessageControllerTest {
    private final MessageRepository repository = mock(MessageRepository.class);
    private final MessageService service = mock(MessageService.class);
    private final LanguageResolver languageResolver = mock(LanguageResolver.class);
    private final MessageController controller = new MessageController(repository, service, languageResolver);

    @Test
    void listEscapesCaseFoldedSearchAndReturnsNoCachePage() {
        Message message = message("ui.action.save", "en", "Save");
        when(repository.search(eq("ui.action.save"), eq("%a\\%\\_\\\\b%"), eq("en"), any(Pageable.class)))
            .thenReturn(new PageImpl<>(List.of(message), PageRequest.of(1, 10), 11));
        Locale previous = Locale.getDefault();
        try {
            Locale.setDefault(Locale.forLanguageTag("tr"));

            var response = controller.list("ui.action.save", "A%_\\B", "en", 1, 10);

            assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
            assertThat(response.getBody().items()).extracting(MessageResponse::text).containsExactly("Save");
            assertThat(response.getBody().page()).isEqualTo(1);
            assertThat(response.getBody().totalItems()).isEqualTo(11);
        } finally {
            Locale.setDefault(previous);
        }
    }

    @Test
    void bundleReportsResolvedLanguageAndNoCache() {
        when(languageResolver.resolve("pl", "en;q=0.8")).thenReturn("pl");
        when(service.bundle("pl")).thenReturn(Map.of("ui.action.save", "Zapisz"));

        var response = controller.bundle("pl", "en;q=0.8");

        assertThat(response.getHeaders().getFirst(HttpHeaders.CONTENT_LANGUAGE)).isEqualTo("pl");
        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
        assertThat(response.getBody().language()).isEqualTo("pl");
        assertThat(response.getBody().messages()).containsEntry("ui.action.save", "Zapisz");
    }

    @Test
    void getReturnsNoCacheResponseAndMasksMissingMessage() {
        Message message = message("ui.action.save", "en", "Save");
        when(repository.findById(message.getId())).thenReturn(Optional.of(message));
        UUID missing = UUID.randomUUID();
        when(repository.findById(missing)).thenReturn(Optional.empty());

        var response = controller.get(message.getId());

        assertThat(response.getHeaders().getCacheControl()).isEqualTo("no-cache");
        assertThat(response.getBody().id()).isEqualTo(message.getId());
        assertThatThrownBy(() -> controller.get(missing)).isInstanceOf(NotFoundProblem.class);
    }

    @Test
    void adminMutationsUseAuthenticatedActorAndResourceLocations() {
        Authentication authentication = mock(Authentication.class);
        when(authentication.getName()).thenReturn("admin");
        MessageRequest request = new MessageRequest("ui.action.save", "en", "Save", null);
        Message created = message("ui.action.save", "en", "Save");
        Message updated = message("ui.action.save", "en", "Save now");
        when(service.create("admin", request)).thenReturn(created);
        when(service.update("admin", created.getId(), request)).thenReturn(updated);

        var createResponse = controller.create(request, authentication);
        MessageResponse updateResponse = controller.update(created.getId(), request, authentication);
        controller.delete(created.getId(), authentication);

        assertThat(createResponse.getStatusCode().value()).isEqualTo(201);
        assertThat(createResponse.getHeaders().getLocation().getPath())
            .isEqualTo("/api/v1/messages/" + created.getId());
        assertThat(updateResponse.text()).isEqualTo("Save now");
        verify(service).delete("admin", created.getId());
    }

    private static Message message(String key, String language, String text) {
        return new Message(key, language, text, null, Instant.EPOCH, Instant.EPOCH);
    }
}
