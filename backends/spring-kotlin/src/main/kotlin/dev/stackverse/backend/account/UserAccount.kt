package dev.stackverse.backend.account

import com.fasterxml.jackson.annotation.JsonValue
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

enum class UserAccountStatus(@get:JsonValue val wire: String) {
    ACTIVE("active"),
    BLOCKED("blocked"),
}

/** App-level account, lazily provisioned from JWTs (SPEC rule 16) — identity itself is Keycloak's. */
@Entity
@Table(name = "user_accounts")
class UserAccount(
    @Id
    val username: String,
    val firstSeen: Instant,
    var lastSeen: Instant,
    @Enumerated(EnumType.STRING)
    var status: UserAccountStatus,
    var blockedReason: String?,
)
