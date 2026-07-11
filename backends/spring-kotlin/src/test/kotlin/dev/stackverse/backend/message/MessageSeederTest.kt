package dev.stackverse.backend.message

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito.mock
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.boot.DefaultApplicationArguments
import tools.jackson.databind.ObjectMapper
import java.nio.file.Path

class MessageSeederTest {

    @TempDir
    lateinit var temporaryDirectory: Path

    @Test
    fun `missing seed directory fails fast with an actionable error`() {
        val repository = mock(MessageRepository::class.java)
        val missingDirectory = temporaryDirectory.resolve("missing")
        val seeder = MessageSeeder(repository, ObjectMapper(), SeedProperties(missingDirectory.toString()))

        val failure = assertThrows<IllegalStateException> {
            seeder.run(DefaultApplicationArguments())
        }

        assertThat(failure.message)
            .contains("Message seed directory not found")
            .contains(missingDirectory.toAbsolutePath().toString())
            .contains("SEED_MESSAGES_DIR")
        verifyNoInteractions(repository)
    }
}
