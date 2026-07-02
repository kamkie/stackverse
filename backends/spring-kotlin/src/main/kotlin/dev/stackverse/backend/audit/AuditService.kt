package dev.stackverse.backend.audit

import dev.stackverse.backend.common.nowUtc
import org.springframework.stereotype.Service
import tools.jackson.databind.ObjectMapper

@Service
class AuditService(
    private val repository: AuditRepository,
    private val objectMapper: ObjectMapper,
) {

    fun record(actor: String, action: String, targetType: String, targetId: String, detail: Map<String, Any?>? = null) {
        repository.save(
            AuditEntry(
                actor = actor,
                action = action,
                targetType = targetType,
                targetId = targetId,
                detail = detail?.let { objectMapper.writeValueAsString(it) },
                createdAt = nowUtc(),
            ),
        )
    }
}
