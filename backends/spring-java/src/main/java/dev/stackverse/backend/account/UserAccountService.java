package dev.stackverse.backend.account;

import static dev.stackverse.backend.common.Time.nowUtc;

import dev.stackverse.backend.audit.AuditService;
import dev.stackverse.backend.common.ConflictProblem;
import dev.stackverse.backend.common.NotFoundProblem;
import dev.stackverse.backend.common.Validator;
import dev.stackverse.backend.common.Logging;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class UserAccountService {
    private final UserAccountRepository repository;
    private final AuditService auditService;
    private final org.slf4j.Logger log = LoggerFactory.getLogger(getClass());

    public UserAccountService(UserAccountRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    /** SPEC rule 16: upsert on every authenticated request. */
    public UserAccount recordSeen(String username) {
        repository.upsertSeen(username, nowUtc());
        return repository.findById(username).orElseThrow();
    }

    /** SPEC rule 17: block/unblock with audit; admins cannot block themselves. */
    public UserAccount setStatus(String actor, String username, UserAccountStatus status, String reason) {
        UserAccount account = repository.findById(username).orElseThrow(NotFoundProblem::new);
        if (status == UserAccountStatus.BLOCKED) {
            Validator validator = new Validator();
            validator.check(reason != null && !reason.isBlank(), "reason", "validation.block.reason.required");
            validator.check(reason == null || reason.length() <= 1000, "reason", "validation.block.reason.too-long");
            validator.throwIfInvalid();
            if (username.equals(actor)) {
                throw new ConflictProblem("Admins cannot block themselves.");
            }
            account.setStatus(UserAccountStatus.BLOCKED);
            account.setBlockedReason(reason);
            auditService.record(actor, "user.blocked", "user", username, Map.of("reason", reason));
            Logging.logEvent(log, Level.INFO, "user_blocked", "success", "User account blocked",
                "actor", actor, "resource_type", "user", "resource_id", username);
        } else {
            account.setStatus(UserAccountStatus.ACTIVE);
            account.setBlockedReason(null);
            auditService.record(actor, "user.unblocked", "user", username);
            Logging.logEvent(log, Level.INFO, "user_unblocked", "success", "User account unblocked",
                "actor", actor, "resource_type", "user", "resource_id", username);
        }
        return account;
    }
}
