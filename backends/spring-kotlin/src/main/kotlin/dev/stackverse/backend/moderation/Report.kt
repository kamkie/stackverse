package dev.stackverse.backend.moderation

import com.fasterxml.jackson.annotation.JsonValue
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

enum class ReportReason(@get:JsonValue val wire: String) {
    SPAM("spam"),
    OFFENSIVE("offensive"),
    BROKEN_LINK("broken-link"),
    OTHER("other"),
    ;

    companion object {
        fun fromWire(value: String?): ReportReason? = entries.find { it.wire == value }
    }
}

enum class ReportStatus(@get:JsonValue val wire: String) {
    OPEN("open"),
    DISMISSED("dismissed"),
    ACTIONED("actioned"),
}

@Entity
@Table(name = "reports")
class Report(
    @Id
    val id: UUID = UUID.randomUUID(),
    val bookmarkId: UUID,
    val reporter: String,
    @Enumerated(EnumType.STRING)
    var reason: ReportReason,
    var comment: String?,
    @Enumerated(EnumType.STRING)
    var status: ReportStatus,
    var resolvedBy: String?,
    var resolvedAt: Instant?,
    var resolutionNote: String?,
    val createdAt: Instant,
)
