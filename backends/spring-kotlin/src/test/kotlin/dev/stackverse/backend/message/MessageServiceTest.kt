package dev.stackverse.backend.message

import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.common.ConflictProblem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
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
}
