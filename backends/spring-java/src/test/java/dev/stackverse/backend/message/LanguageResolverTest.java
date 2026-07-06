package dev.stackverse.backend.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Set;
import org.junit.jupiter.api.Test;

class LanguageResolverTest {
    private final MessageRepository repository = mock(MessageRepository.class);
    private final LanguageResolver resolver = new LanguageResolver(repository);

    @Test
    void explicitLanguageWinsWhenSupported() {
        when(repository.findDistinctLanguages()).thenReturn(Set.of("en", "pl"));

        assertThat(resolver.resolve("pl", "en;q=1")).isEqualTo("pl");
    }

    @Test
    void acceptLanguageFallsBackByQualityThenDefault() {
        when(repository.findDistinctLanguages()).thenReturn(Set.of("en", "pl"));

        assertThat(resolver.resolve(null, "fr-CA,pl;q=0.9,en;q=0.8")).isEqualTo("pl");
        assertThat(resolver.resolve(null, "fr-CA")).isEqualTo("en");
    }
}
