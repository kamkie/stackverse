package dev.stackverse.backend;

import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

record Bookmark(UUID id, String owner, String url, String title, String notes, List<String> tags,
                String visibility, String status, Instant createdAt, Instant updatedAt) {
    boolean visibleTo(String caller) {
        return owner.equals(caller) || ("public".equals(visibility) && "active".equals(status));
    }
}

record Message(UUID id, String key, String language, String text, String description,
               Instant createdAt, Instant updatedAt) {
}

record Report(UUID id, UUID bookmarkId, String reporter, String reason, String comment, String status,
              String resolvedBy, Instant resolvedAt, String resolutionNote, Instant createdAt) {
}

record UserAccount(String username, Instant firstSeen, Instant lastSeen, String status,
                   String blockedReason, long bookmarkCount) {
}

record AuditEntry(UUID id, String actor, String action, String targetType, String targetId,
                  String detail, Instant createdAt) {
}

record BookmarkInput(String url, String title, String notes, List<String> tags, String visibility) {
}

record MessageInput(String key, String language, String text, String description) {
}

record ReportInput(String reason, String comment) {
}

record ResolutionInput(String resolution, String note) {
}

record BookmarkStatusInput(String status, String note) {
}

record UserStatusInput(String status, String reason) {
}

record StatusChange(String previous, Bookmark bookmark) {
}

record DayCount(LocalDate date, long count) {
}

record Page<T>(List<T> items, int page, int size, long totalItems) {
}

record Cursor(Instant createdAt, UUID id) {
    String encode() {
        String raw = createdAt.toString() + "|" + id;
        return Base64.getUrlEncoder().withoutPadding().encodeToString(raw.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    static Cursor decode(String raw) {
        StackverseProblem malformed = StackverseProblem.badRequest("The cursor is malformed or unresolvable.");
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), java.nio.charset.StandardCharsets.UTF_8);
            int separator = decoded.indexOf('|');
            if (separator < 0) {
                throw malformed;
            }
            return new Cursor(Instant.parse(decoded.substring(0, separator)),
                    UUID.fromString(decoded.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException error) {
            throw malformed;
        }
    }
}
