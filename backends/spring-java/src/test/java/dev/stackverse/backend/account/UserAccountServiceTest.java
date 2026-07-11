package dev.stackverse.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.stackverse.backend.audit.AuditService;
import dev.stackverse.backend.common.ConflictProblem;
import dev.stackverse.backend.common.FieldViolation;
import dev.stackverse.backend.common.NotFoundProblem;
import dev.stackverse.backend.common.ValidationProblem;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class UserAccountServiceTest {
    private final UserAccountRepository repository = mock(UserAccountRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final UserAccountService service = new UserAccountService(repository, auditService);

    @Test
    void recordSeenUsesAtomicUpsertThenReloadsAccount() {
        UserAccount account = account("alice", UserAccountStatus.ACTIVE, null);
        when(repository.findById("alice")).thenReturn(Optional.of(account));

        assertThat(service.recordSeen("alice")).isSameAs(account);

        InOrder order = inOrder(repository);
        order.verify(repository).upsertSeen(eq("alice"), any(Instant.class));
        order.verify(repository).findById("alice");
    }

    @Test
    void blockSetsReasonAndRecordsAudit() {
        UserAccount account = account("alice", UserAccountStatus.ACTIVE, null);
        when(repository.findById("alice")).thenReturn(Optional.of(account));

        UserAccount blocked = service.setStatus("admin", "alice", UserAccountStatus.BLOCKED, "policy violation");

        assertThat(blocked).isSameAs(account);
        assertThat(blocked.getStatus()).isEqualTo(UserAccountStatus.BLOCKED);
        assertThat(blocked.getBlockedReason()).isEqualTo("policy violation");
        verify(auditService).record(
            "admin",
            "user.blocked",
            "user",
            "alice",
            Map.of("reason", "policy violation")
        );
    }

    @Test
    void unblockClearsReasonAndRecordsAudit() {
        UserAccount account = account("alice", UserAccountStatus.BLOCKED, "old reason");
        when(repository.findById("alice")).thenReturn(Optional.of(account));

        UserAccount active = service.setStatus("admin", "alice", UserAccountStatus.ACTIVE, "ignored");

        assertThat(active.getStatus()).isEqualTo(UserAccountStatus.ACTIVE);
        assertThat(active.getBlockedReason()).isNull();
        verify(auditService).record("admin", "user.unblocked", "user", "alice");
    }

    @Test
    void blockValidatesReasonBeforeMutatingAccount() {
        UserAccount account = account("alice", UserAccountStatus.ACTIVE, null);
        when(repository.findById("alice")).thenReturn(Optional.of(account));

        ValidationProblem missing = catchThrowableOfType(
            ValidationProblem.class,
            () -> service.setStatus("admin", "alice", UserAccountStatus.BLOCKED, " ")
        );
        ValidationProblem tooLong = catchThrowableOfType(
            ValidationProblem.class,
            () -> service.setStatus("admin", "alice", UserAccountStatus.BLOCKED, "x".repeat(1001))
        );

        assertThat(missing.getViolations())
            .extracting(FieldViolation::messageKey)
            .contains("validation.block.reason.required");
        assertThat(tooLong.getViolations())
            .extracting(FieldViolation::messageKey)
            .contains("validation.block.reason.too-long");
        assertThat(account.getStatus()).isEqualTo(UserAccountStatus.ACTIVE);
        verifyNoInteractions(auditService);
    }

    @Test
    void adminCannotBlockSelf() {
        UserAccount account = account("admin", UserAccountStatus.ACTIVE, null);
        when(repository.findById("admin")).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> service.setStatus("admin", "admin", UserAccountStatus.BLOCKED, "reason"))
            .isInstanceOf(ConflictProblem.class)
            .hasMessageContaining("themselves");

        assertThat(account.getStatus()).isEqualTo(UserAccountStatus.ACTIVE);
        verify(auditService, never()).record(any(), any(), any(), any(), any());
    }

    @Test
    void statusChangeMasksMissingUser() {
        when(repository.findById("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.setStatus("admin", "missing", UserAccountStatus.ACTIVE, null))
            .isInstanceOf(NotFoundProblem.class);
    }

    private static UserAccount account(String username, UserAccountStatus status, String blockedReason) {
        return new UserAccount(username, Instant.EPOCH, Instant.EPOCH, status, blockedReason);
    }
}
