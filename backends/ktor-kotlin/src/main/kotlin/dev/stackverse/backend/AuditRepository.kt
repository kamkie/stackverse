package dev.stackverse.backend

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import org.postgresql.util.PGobject
import java.sql.Connection
import java.sql.ResultSet
import java.util.UUID

class AuditRepository(private val db: Database, private val mapper: ObjectMapper) {
    fun record(connection: Connection, actor: String, action: String, targetType: String, targetId: String, detail: Map<String, Any?>? = null) {
        val json = detail?.let {
            PGobject().apply {
                type = "jsonb"
                value = mapper.writeValueAsString(it)
            }
        }
        connection.execute(
            """
            insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at)
            values (?, ?, ?, ?, ?, ?, ?)
            """.trimIndent(),
            UUID.randomUUID(), actor, action, targetType, targetId, json, nowUtc(),
        )
    }

    suspend fun list(filter: AuditFilter, page: Int, size: Int): PageResponse<AuditEntryResponse> = db.read {
        var clause = Clause("1 = 1")
        filter.actor?.let { clause = clause.and("actor = ?", it) }
        filter.action?.let { clause = clause.and("action = ?", it) }
        filter.targetType?.let { clause = clause.and("target_type = ?", it) }
        filter.targetId?.let { clause = clause.and("target_id = ?", it) }
        filter.from?.let { clause = clause.and("created_at >= ?", it) }
        filter.to?.let { clause = clause.and("created_at <= ?", it) }
        val total = queryLong("select count(*) from audit_entries where ${clause.sql}", clause.args)
        val items = query(
            """
            select * from audit_entries
            where ${clause.sql}
            order by created_at desc
            limit ? offset ?
            """.trimIndent(),
            clause.args + listOf(size, page * size),
        ) { it.toAudit(mapper) }
        PageResponse(items, page, size, total, pages(total, size))
    }

    private fun ResultSet.toAudit(mapper: ObjectMapper): AuditEntryResponse {
        val detailJson = stringOrNull("detail")
        val detail = detailJson?.let { mapper.readValue(it, object : TypeReference<Map<String, Any?>>() {}) }
        return AuditEntryResponse(
            id = uuid("id"),
            actor = getString("actor"),
            action = getString("action"),
            targetType = getString("target_type"),
            targetId = getString("target_id"),
            detail = detail,
            createdAt = instant("created_at"),
        )
    }
}
