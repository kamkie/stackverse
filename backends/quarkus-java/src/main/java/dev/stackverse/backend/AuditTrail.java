package dev.stackverse.backend;

import static dev.stackverse.backend.PersistenceSupport.execute;
import static dev.stackverse.backend.PersistenceSupport.now;
import static dev.stackverse.backend.PersistenceSupport.params;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.util.Map;
import java.util.UUID;

@ApplicationScoped
final class AuditTrail {
    private final ObjectMapper mapper;

    AuditTrail(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    void record(
            Connection connection,
            String actor,
            String action,
            String targetType,
            String targetId,
            Map<String, Object> detail) {
        String detailJson;
        try {
            detailJson = detail == null ? null : mapper.writeValueAsString(detail);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException(error);
        }
        execute(
                connection,
                "insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at)"
                        + " values (?, ?, ?, ?, ?, cast(? as jsonb), ?)",
                params(UUID.randomUUID(), actor, action, targetType, targetId, detailJson, now()));
    }
}
