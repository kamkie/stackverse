package dev.stackverse.backend.audit;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Append-only audit rows: inserted and read, never updated or deleted. */
@Entity
@Table(name = "audit_entries")
public class AuditEntry {
    @Id
    private UUID id;
    private String actor;
    private String action;
    private String targetType;
    private String targetId;
    @JdbcTypeCode(SqlTypes.JSON)
    private String detail;
    private Instant createdAt;

    protected AuditEntry() {
    }

    public AuditEntry(String actor, String action, String targetType, String targetId, String detail, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.actor = actor;
        this.action = action;
        this.targetType = targetType;
        this.targetId = targetId;
        this.detail = detail;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public String getActor() {
        return actor;
    }

    public String getAction() {
        return action;
    }

    public String getTargetType() {
        return targetType;
    }

    public String getTargetId() {
        return targetId;
    }

    public String getDetail() {
        return detail;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
