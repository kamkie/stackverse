package dev.stackverse.backend.moderation;

import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "reports")
public class Report {
    @Id
    private UUID id;
    private UUID bookmarkId;
    private String reporter;
    @Enumerated(EnumType.STRING)
    private ReportReason reason;
    private String comment;
    @Enumerated(EnumType.STRING)
    private ReportStatus status;
    private String resolvedBy;
    private Instant resolvedAt;
    private String resolutionNote;
    private Instant createdAt;

    protected Report() {
    }

    public Report(UUID bookmarkId, String reporter, ReportReason reason, String comment, ReportStatus status, String resolvedBy, Instant resolvedAt, String resolutionNote, Instant createdAt) {
        this.id = UUID.randomUUID();
        this.bookmarkId = bookmarkId;
        this.reporter = reporter;
        this.reason = reason;
        this.comment = comment;
        this.status = status;
        this.resolvedBy = resolvedBy;
        this.resolvedAt = resolvedAt;
        this.resolutionNote = resolutionNote;
        this.createdAt = createdAt;
    }

    public UUID getId() {
        return id;
    }

    public UUID getBookmarkId() {
        return bookmarkId;
    }

    public String getReporter() {
        return reporter;
    }

    public ReportReason getReason() {
        return reason;
    }

    public void setReason(ReportReason reason) {
        this.reason = reason;
    }

    public String getComment() {
        return comment;
    }

    public void setComment(String comment) {
        this.comment = comment;
    }

    public ReportStatus getStatus() {
        return status;
    }

    public void setStatus(ReportStatus status) {
        this.status = status;
    }

    public String getResolvedBy() {
        return resolvedBy;
    }

    public void setResolvedBy(String resolvedBy) {
        this.resolvedBy = resolvedBy;
    }

    public Instant getResolvedAt() {
        return resolvedAt;
    }

    public void setResolvedAt(Instant resolvedAt) {
        this.resolvedAt = resolvedAt;
    }

    public String getResolutionNote() {
        return resolutionNote;
    }

    public void setResolutionNote(String resolutionNote) {
        this.resolutionNote = resolutionNote;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
