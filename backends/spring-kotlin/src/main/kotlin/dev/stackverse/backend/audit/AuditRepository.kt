package dev.stackverse.backend.audit

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.JpaSpecificationExecutor
import java.util.UUID

interface AuditRepository :
    JpaRepository<AuditEntry, UUID>,
    JpaSpecificationExecutor<AuditEntry>
