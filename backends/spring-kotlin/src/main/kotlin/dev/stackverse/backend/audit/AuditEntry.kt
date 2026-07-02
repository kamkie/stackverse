package dev.stackverse.backend.audit

import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import org.hibernate.annotations.JdbcTypeCode
import org.hibernate.type.SqlTypes
import java.time.Instant
import java.util.UUID

/** Append-only (SPEC rule 18) — rows are inserted and read, never updated or deleted. */
@Entity
@Table(name = "audit_entries")
class AuditEntry(
    @Id
    val id: UUID = UUID.randomUUID(),
    val actor: String,
    val action: String,
    val targetType: String,
    val targetId: String,
    @JdbcTypeCode(SqlTypes.JSON)
    val detail: String?,
    val createdAt: Instant,
)
