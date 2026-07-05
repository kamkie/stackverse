package dev.stackverse.backend.message

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`

class LanguageResolverTest {

    private val repository = mock(MessageRepository::class.java)
    private val resolver = LanguageResolver(repository)

    @Test
    fun `explicit supported language wins over accept language`() {
        `when`(repository.findDistinctLanguages()).thenReturn(setOf("en", "pl"))

        assertThat(resolver.resolve("pl", "en;q=1.0")).isEqualTo("pl")
    }

    @Test
    fun `unsupported explicit language falls through to first supported accept-language range`() {
        `when`(repository.findDistinctLanguages()).thenReturn(setOf("en", "pl"))

        assertThat(resolver.resolve("de", "fr-CA;q=0.9, pl;q=0.8, en;q=0.7")).isEqualTo("pl")
    }

    @Test
    fun `malformed accept language falls back to default language`() {
        `when`(repository.findDistinctLanguages()).thenReturn(setOf("en", "pl"))

        assertThat(resolver.resolve(null, "pl;q=not-a-number")).isEqualTo(DEFAULT_LANGUAGE)
    }

    @Test
    fun `missing language preferences fall back to default language`() {
        `when`(repository.findDistinctLanguages()).thenReturn(setOf("pl"))

        assertThat(resolver.resolve(null, null)).isEqualTo(DEFAULT_LANGUAGE)
    }
}
