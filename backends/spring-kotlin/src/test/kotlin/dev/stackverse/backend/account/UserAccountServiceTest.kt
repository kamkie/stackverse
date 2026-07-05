package dev.stackverse.backend.account

import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.common.ConflictProblem
import dev.stackverse.backend.common.FieldViolation
import dev.stackverse.backend.common.ValidationProblem
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import java.time.Instant
import java.util.Optional

class UserAccountServiceTest {

    private val repository = mock(UserAccountRepository::class.java)
    private val auditService = mock(AuditService::class.java)
    private val service = UserAccountService(repository, auditService)

    @Test
    fun `blocking a user records reason and audit entry`() {
        val account = account("alice")
        `when`(repository.findById("alice")).thenReturn(Optional.of(account))

        val result = service.setStatus("admin", "alice", UserAccountStatus.BLOCKED, "policy violation")

        assertThat(result.status).isEqualTo(UserAccountStatus.BLOCKED)
        assertThat(result.blockedReason).isEqualTo("policy violation")
        verify(auditService).record(
            "admin",
            "user.blocked",
            "user",
            "alice",
            mapOf("reason" to "policy violation"),
        )
    }

    @Test
    fun `unblocking a user clears reason and records audit entry`() {
        val account = account("alice", status = UserAccountStatus.BLOCKED, blockedReason = "policy violation")
        `when`(repository.findById("alice")).thenReturn(Optional.of(account))

        val result = service.setStatus("admin", "alice", UserAccountStatus.ACTIVE, null)

        assertThat(result.status).isEqualTo(UserAccountStatus.ACTIVE)
        assertThat(result.blockedReason).isNull()
        verify(auditService).record("admin", "user.unblocked", "user", "alice", null)
    }

    @Test
    fun `blocking requires a nonblank reason before audit side effects`() {
        val account = account("alice")
        `when`(repository.findById("alice")).thenReturn(Optional.of(account))

        val problem = assertThrows<ValidationProblem> {
            service.setStatus("admin", "alice", UserAccountStatus.BLOCKED, " ")
        }

        assertThat(problem.violations).containsExactly(FieldViolation("reason", "validation.block.reason.required"))
        assertThat(account.status).isEqualTo(UserAccountStatus.ACTIVE)
        verifyNoInteractions(auditService)
    }

    @Test
    fun `admins cannot block themselves`() {
        val account = account("admin")
        `when`(repository.findById("admin")).thenReturn(Optional.of(account))

        val problem = assertThrows<ConflictProblem> {
            service.setStatus("admin", "admin", UserAccountStatus.BLOCKED, "policy violation")
        }

        assertThat(problem.detail).isEqualTo("Admins cannot block themselves.")
        assertThat(account.status).isEqualTo(UserAccountStatus.ACTIVE)
        verifyNoInteractions(auditService)
    }

    private fun account(
        username: String,
        status: UserAccountStatus = UserAccountStatus.ACTIVE,
        blockedReason: String? = null,
    ) = UserAccount(
        username = username,
        firstSeen = Instant.parse("2026-07-05T12:00:00Z"),
        lastSeen = Instant.parse("2026-07-05T12:00:00Z"),
        status = status,
        blockedReason = blockedReason,
    )
}
