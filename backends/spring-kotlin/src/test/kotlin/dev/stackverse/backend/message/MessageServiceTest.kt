package dev.stackverse.backend.message

import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.common.ConflictProblem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.dao.DataIntegrityViolationException
import java.time.Instant
import java.util.Optional
import java.util.UUID

class MessageServiceTest {

    private val repository = mock(MessageRepository::class.java)
    private val auditService = mock(AuditService::class.java)
    private val service = MessageService(repository, auditService)

    @Test
    fun `create maps duplicate unique constraint race to conflict`() {
        `when`(repository.existsByKeyAndLanguage("race.message", "en")).thenReturn(false)
        `when`(repository.saveAndFlush(any(Message::class.java)))
            .thenThrow(DataIntegrityViolationException("uq_messages_key_language"))

        val problem = assertThrows<ConflictProblem> {
            service.create(
                "admin",
                MessageRequest(key = "race.message", language = "en", text = "first"),
            )
        }

        assertThat(problem.detail).contains("race.message", "en", "already exists")
        verifyNoInteractions(auditService)
    }

    @Test
    fun `update maps duplicate unique constraint race to conflict`() {
        val message = Message(
            id = UUID.randomUUID(),
            key = "current.message",
            language = "en",
            text = "current",
            description = null,
            createdAt = Instant.parse("2026-01-01T00:00:00Z"),
            updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
        )
        `when`(repository.findById(message.id)).thenReturn(Optional.of(message))
        `when`(repository.findByKeyAndLanguage("race.message", "en")).thenReturn(null)
        doThrow(DataIntegrityViolationException("uq_messages_key_language")).`when`(repository).flush()

        val problem = assertThrows<ConflictProblem> {
            service.update(
                "admin",
                message.id,
                MessageRequest(key = "race.message", language = "en", text = "updated"),
            )
        }

        assertThat(problem.detail).contains("race.message", "en", "already exists")
        verifyNoInteractions(auditService)
    }

    @Test
    fun `update rejects an existing key and language pair before mutating the message`() {
        val message = message(key = "current.message", language = "en", text = "current")
        val duplicate = message(key = "race.message", language = "en", text = "other")
        `when`(repository.findById(message.id)).thenReturn(Optional.of(message))
        `when`(repository.findByKeyAndLanguage("race.message", "en")).thenReturn(duplicate)

        val problem = assertThrows<ConflictProblem> {
            service.update(
                "admin",
                message.id,
                MessageRequest(key = "race.message", language = "en", text = "updated"),
            )
        }

        assertThat(problem.detail).contains("race.message", "en", "already exists")
        assertThat(message.key).isEqualTo("current.message")
        assertThat(message.text).isEqualTo("current")
        verify(repository, never()).flush()
        verifyNoInteractions(auditService)
    }

    @Test
    fun `bundle overlays requested language and falls back to english for missing keys`() {
        `when`(repository.findByLanguageIn(setOf(DEFAULT_LANGUAGE, "pl"))).thenReturn(
            listOf(
                message(key = "ui.nav.home", language = "en", text = "Home"),
                message(key = "ui.nav.home", language = "pl", text = "Start"),
                message(key = "ui.nav.settings", language = "en", text = "Settings"),
            ),
        )

        val bundle = service.bundle("pl")

        assertThat(bundle).containsEntry("ui.nav.home", "Start")
        assertThat(bundle).containsEntry("ui.nav.settings", "Settings")
        assertThat(bundle.keys).containsExactly("ui.nav.home", "ui.nav.settings")
        verifyNoInteractions(auditService)
    }

    private fun message(
        key: String,
        language: String,
        text: String,
        id: UUID = UUID.randomUUID(),
    ) = Message(
        id = id,
        key = key,
        language = language,
        text = text,
        description = null,
        createdAt = Instant.parse("2026-01-01T00:00:00Z"),
        updatedAt = Instant.parse("2026-01-01T00:00:00Z"),
    )
}
