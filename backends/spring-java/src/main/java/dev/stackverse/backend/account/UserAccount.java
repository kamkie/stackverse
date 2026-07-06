package dev.stackverse.backend.account;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;

/** App-level account, lazily provisioned from JWTs. */
@Entity
@Table(name = "user_accounts")
public class UserAccount {
    @Id
    private String username;
    private Instant firstSeen;
    private Instant lastSeen;
    @Enumerated(EnumType.STRING)
    private UserAccountStatus status;
    private String blockedReason;

    protected UserAccount() {
    }

    public UserAccount(String username, Instant firstSeen, Instant lastSeen, UserAccountStatus status, String blockedReason) {
        this.username = username;
        this.firstSeen = firstSeen;
        this.lastSeen = lastSeen;
        this.status = status;
        this.blockedReason = blockedReason;
    }

    public String getUsername() {
        return username;
    }

    public Instant getFirstSeen() {
        return firstSeen;
    }

    public Instant getLastSeen() {
        return lastSeen;
    }

    public void setLastSeen(Instant lastSeen) {
        this.lastSeen = lastSeen;
    }

    public UserAccountStatus getStatus() {
        return status;
    }

    public void setStatus(UserAccountStatus status) {
        this.status = status;
    }

    public String getBlockedReason() {
        return blockedReason;
    }

    public void setBlockedReason(String blockedReason) {
        this.blockedReason = blockedReason;
    }
}
