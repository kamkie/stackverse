package dev.stackverse.backend.moderation;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record ReportResponse(
    UUID id,
    UUID bookmarkId,
    String reporter,
    ReportReason reason,
    String comment,
    ReportStatus status,
    String resolvedBy,
    Instant resolvedAt,
    String resolutionNote,
    Instant createdAt
) {
    public static ReportResponse of(Report report) {
        return new ReportResponse(
            report.getId(),
            report.getBookmarkId(),
            report.getReporter(),
            report.getReason(),
            report.getComment(),
            report.getStatus(),
            report.getResolvedBy(),
            report.getResolvedAt(),
            report.getResolutionNote(),
            report.getCreatedAt()
        );
    }
}
