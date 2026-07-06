package dev.stackverse.backend.audit;

import static dev.stackverse.backend.common.RequestValidation.requireValidPaging;

import com.fasterxml.jackson.annotation.JsonInclude;
import dev.stackverse.backend.common.PageResponse;
import jakarta.persistence.criteria.Predicate;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

@JsonInclude(JsonInclude.Include.NON_NULL)
record AuditEntryResponse(
    UUID id,
    String actor,
    String action,
    String targetType,
    String targetId,
    Map<String, Object> detail,
    Instant createdAt
) {
}

@RestController
@RequestMapping("/api/v1/admin/audit-log")
@PreAuthorize("hasRole('admin')")
public class AdminAuditController {
    private final AuditRepository repository;
    private final ObjectMapper objectMapper;

    public AdminAuditController(AuditRepository repository, ObjectMapper objectMapper) {
        this.repository = repository;
        this.objectMapper = objectMapper;
    }

    @GetMapping
    public PageResponse<AuditEntryResponse> list(
        @RequestParam(name = "actor", required = false) String actor,
        @RequestParam(name = "action", required = false) String action,
        @RequestParam(name = "targetType", required = false) String targetType,
        @RequestParam(name = "targetId", required = false) String targetId,
        @RequestParam(name = "from", required = false) Instant from,
        @RequestParam(name = "to", required = false) Instant to,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        requireValidPaging(page, size);
        Specification<AuditEntry> specification = (root, query, cb) -> {
            var predicates = new ArrayList<Predicate>();
            if (actor != null) {
                predicates.add(cb.equal(root.get("actor"), actor));
            }
            if (action != null) {
                predicates.add(cb.equal(root.get("action"), action));
            }
            if (targetType != null) {
                predicates.add(cb.equal(root.get("targetType"), targetType));
            }
            if (targetId != null) {
                predicates.add(cb.equal(root.get("targetId"), targetId));
            }
            if (from != null) {
                predicates.add(cb.greaterThanOrEqualTo(root.get("createdAt"), from));
            }
            if (to != null) {
                predicates.add(cb.lessThanOrEqualTo(root.get("createdAt"), to));
            }
            return cb.and(predicates.toArray(Predicate[]::new));
        };
        return PageResponse.of(
            repository.findAll(specification, PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt"))),
            this::toResponse
        );
    }

    private AuditEntryResponse toResponse(AuditEntry entry) {
        return new AuditEntryResponse(
            entry.getId(),
            entry.getActor(),
            entry.getAction(),
            entry.getTargetType(),
            entry.getTargetId(),
            readDetail(entry.getDetail()),
            entry.getCreatedAt()
        );
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readDetail(String detail) {
        if (detail == null) {
            return null;
        }
        return objectMapper.readValue(detail, Map.class);
    }
}
