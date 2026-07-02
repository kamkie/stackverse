package dev.stackverse.backend.audit

import com.fasterxml.jackson.annotation.JsonInclude
import dev.stackverse.backend.common.PageResponse
import dev.stackverse.backend.common.requireValidPaging
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.data.jpa.domain.Specification
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import tools.jackson.databind.ObjectMapper
import java.time.Instant
import java.util.UUID

@JsonInclude(JsonInclude.Include.NON_NULL)
data class AuditEntryResponse(
    val id: UUID,
    val actor: String,
    val action: String,
    val targetType: String,
    val targetId: String,
    val detail: Map<String, Any?>?,
    val createdAt: Instant,
)

@RestController
@RequestMapping("/api/v1/admin/audit-log")
@PreAuthorize("hasRole('admin')")
class AdminAuditController(
    private val repository: AuditRepository,
    private val objectMapper: ObjectMapper,
) {

    @GetMapping
    fun list(
        @RequestParam actor: String?,
        @RequestParam action: String?,
        @RequestParam targetType: String?,
        @RequestParam targetId: String?,
        @RequestParam from: Instant?,
        @RequestParam to: Instant?,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<AuditEntryResponse> {
        requireValidPaging(page, size)
        val specification = Specification.allOf(
            buildList<Specification<AuditEntry>> {
                actor?.let { add(Specification { root, _, cb -> cb.equal(root.get<String>("actor"), it) }) }
                action?.let { add(Specification { root, _, cb -> cb.equal(root.get<String>("action"), it) }) }
                targetType?.let { add(Specification { root, _, cb -> cb.equal(root.get<String>("targetType"), it) }) }
                targetId?.let { add(Specification { root, _, cb -> cb.equal(root.get<String>("targetId"), it) }) }
                from?.let { add(Specification { root, _, cb -> cb.greaterThanOrEqualTo(root.get("createdAt"), it) }) }
                to?.let { add(Specification { root, _, cb -> cb.lessThanOrEqualTo(root.get("createdAt"), it) }) }
            },
        )
        val pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))
        return PageResponse.of(repository.findAll(specification, pageable)) { it.toResponse() }
    }

    private fun AuditEntry.toResponse() = AuditEntryResponse(
        id = id,
        actor = actor,
        action = action,
        targetType = targetType,
        targetId = targetId,
        detail = detail?.let { objectMapper.readValue(it, Map::class.java).mapKeys { (k, _) -> k.toString() } },
        createdAt = createdAt,
    )
}
