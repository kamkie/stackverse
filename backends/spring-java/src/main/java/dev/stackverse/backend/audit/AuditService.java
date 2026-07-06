package dev.stackverse.backend.audit;

import static dev.stackverse.backend.common.Time.nowUtc;

import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuditService {
    private final AuditRepository repository;
    private final ObjectMapper objectMapper;

    public AuditService(AuditRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    public void record(String actor, String action, String targetType, String targetId) {
        record(actor, action, targetType, targetId, null);
    }

    public void record(String actor, String action, String targetType, String targetId, Map<String, ?> detail) {
        repository.save(new AuditEntry(actor, action, targetType, targetId, writeDetail(detail), nowUtc()));
    }

    private String writeDetail(Map<String, ?> detail) {
        if (detail == null) {
            return null;
        }
        return objectMapper.writeValueAsString(detail);
    }
}
