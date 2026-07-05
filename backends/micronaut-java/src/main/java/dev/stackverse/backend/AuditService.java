package dev.stackverse.backend;

import jakarta.inject.Singleton;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Singleton
final class AuditService {
    private final Database db;
    private final ObjectMapper mapper;

    AuditService(Database db, ObjectMapper mapper) {
        this.db = db;
        this.mapper = mapper;
    }

    void record(Connection connection, String actor, String action, String targetType, String targetId, Map<String, ?> detail) throws SQLException {
        db.update(connection,
                "insert into audit_entries (id, actor, action, target_type, target_id, detail, created_at) values (?, ?, ?, ?, ?, cast(? as jsonb), ?)",
                UUID.randomUUID(), actor, action, targetType, targetId, toJson(detail), WebSupport.now());
    }

    private String toJson(Map<String, ?> detail) {
        if (detail == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(detail);
        } catch (JacksonException ex) {
            throw new IllegalStateException(ex);
        }
    }
}
