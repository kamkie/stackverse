package dev.stackverse.backend.account

import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.common.ConflictProblem
import dev.stackverse.backend.common.NotFoundProblem
import dev.stackverse.backend.common.Validator
import dev.stackverse.backend.common.logEvent
import dev.stackverse.backend.common.nowUtc
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

@Service
@Transactional
class UserAccountService(
    private val repository: UserAccountRepository,
    private val auditService: AuditService,
) {

    // block/unblock events are diagnostics only (docs/LOGGING.md §5) — the audit trail stays authoritative
    private val log = LoggerFactory.getLogger(javaClass)

    /** SPEC rule 16: upsert on every authenticated request; returns the current account state. */
    fun recordSeen(username: String): UserAccount {
        repository.upsertSeen(username, nowUtc())
        return repository.findById(username).orElseThrow()
    }

    /** SPEC rule 17: block/unblock with audit; admins cannot block themselves. */
    fun setStatus(actor: String, username: String, status: UserAccountStatus, reason: String?): UserAccount {
        val account = repository.findById(username).orElseThrow { NotFoundProblem() }
        when (status) {
            UserAccountStatus.BLOCKED -> {
                val validator = Validator()
                validator.check(!reason.isNullOrBlank(), "reason", "validation.block.reason.required")
                validator.check((reason?.length ?: 0) <= 1000, "reason", "validation.block.reason.too-long")
                validator.throwIfInvalid()
                if (username == actor) {
                    throw ConflictProblem("Admins cannot block themselves.")
                }
                account.status = UserAccountStatus.BLOCKED
                account.blockedReason = reason
                auditService.record(actor, "user.blocked", "user", username, mapOf("reason" to reason))
                log.logEvent(
                    Level.INFO, "user_blocked", "success", "User account blocked",
                    "actor" to actor,
                    "resource_type" to "user",
                    "resource_id" to username,
                )
            }
            UserAccountStatus.ACTIVE -> {
                account.status = UserAccountStatus.ACTIVE
                account.blockedReason = null
                auditService.record(actor, "user.unblocked", "user", username)
                log.logEvent(
                    Level.INFO, "user_unblocked", "success", "User account unblocked",
                    "actor" to actor,
                    "resource_type" to "user",
                    "resource_id" to username,
                )
            }
        }
        return account
    }
}
