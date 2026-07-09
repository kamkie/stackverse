package dev.stackverse.backend;

import io.micronaut.core.annotation.Introspected;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import java.sql.Array;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

final class Models {
    static final String PRIVATE = "private";
    static final String PUBLIC = "public";
    static final String ACTIVE = "active";
    static final String HIDDEN = "hidden";
    static final String OPEN = "open";
    static final String DISMISSED = "dismissed";
    static final String ACTIONED = "actioned";
    static final String USER_ACTIVE = "active";
    static final String USER_BLOCKED = "blocked";

    private Models() {
    }

    static Bookmark bookmark(ResultSet rs) throws SQLException {
        return new Bookmark(
                rs.getObject("id", UUID.class),
                rs.getString("owner"),
                rs.getString("url"),
                rs.getString("title"),
                rs.getString("notes"),
                textArray(rs.getArray("tags")),
                rs.getString("visibility"),
                rs.getString("status"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    static Message message(ResultSet rs) throws SQLException {
        return new Message(
                rs.getObject("id", UUID.class),
                rs.getString("key"),
                rs.getString("language"),
                rs.getString("text"),
                rs.getString("description"),
                rs.getTimestamp("created_at").toInstant(),
                rs.getTimestamp("updated_at").toInstant()
        );
    }

    static Report report(ResultSet rs) throws SQLException {
        return new Report(
                rs.getObject("id", UUID.class),
                rs.getObject("bookmark_id", UUID.class),
                rs.getString("reporter"),
                rs.getString("reason"),
                rs.getString("comment"),
                rs.getString("status"),
                rs.getString("resolved_by"),
                rs.getTimestamp("resolved_at") == null ? null : rs.getTimestamp("resolved_at").toInstant(),
                rs.getString("resolution_note"),
                rs.getTimestamp("created_at").toInstant()
        );
    }

    private static List<String> textArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return Arrays.asList((String[]) array.getArray());
    }
}

record Identity(String username, String name, String email, List<String> roles) {
    boolean hasRole(String role) {
        return roles != null && roles.contains(role);
    }
}

record Bookmark(UUID id, String owner, String url, String title, String notes, List<String> tags,
                String visibility, String status, Instant createdAt, Instant updatedAt) {
    boolean visibleTo(String caller) {
        return owner.equals(caller) || (Models.PUBLIC.equals(visibility) && Models.ACTIVE.equals(status));
    }
}

record Message(UUID id, String key, String language, String text, String description,
               Instant createdAt, Instant updatedAt) {
}

record Report(UUID id, UUID bookmarkId, String reporter, String reason, String comment, String status,
              String resolvedBy, Instant resolvedAt, String resolutionNote, Instant createdAt) {
}

record Account(String username, Instant firstSeen, Instant lastSeen, String status, String blockedReason,
               long bookmarkCount) {
}

@Introspected
record BookmarkInput(
        @BookmarkUrl String url,
        @BookmarkTitle String title,
        @Size(max = 4000, message = "validation.notes.too-long") String notes,
        @BookmarkTagCount @BookmarkTagSyntax List<String> tags,
        String visibility) {
}

record BookmarkResponse(UUID id, String url, String title, String notes, List<String> tags, String visibility,
                        String status, String owner, Instant createdAt, Instant updatedAt) {
    static BookmarkResponse from(Bookmark bookmark) {
        return new BookmarkResponse(bookmark.id(), bookmark.url(), bookmark.title(), bookmark.notes(),
                bookmark.tags().stream().sorted().toList(), bookmark.visibility(), bookmark.status(),
                bookmark.owner(), bookmark.createdAt(), bookmark.updatedAt());
    }
}

record BookmarkCursorPage(List<BookmarkResponse> items, String nextCursor) {
}

record TagCount(String tag, long count) {
}

@Introspected
record MessageInput(
        @MessageKey String key,
        @MessageLanguage String language,
        @NotEmpty(message = "validation.message.text.required")
        @Size(max = 2000, message = "validation.message.text.too-long") String text,
        @Size(max = 1000, message = "validation.message.description.too-long") String description) {
}

record MessageResponse(UUID id, String key, String language, String text, String description,
                       Instant createdAt, Instant updatedAt) {
    static MessageResponse from(Message message) {
        return new MessageResponse(message.id(), message.key(), message.language(), message.text(),
                message.description(), message.createdAt(), message.updatedAt());
    }
}

record MessageBundle(String language, Map<String, String> messages) {
}

@Introspected
record ReportInput(
        @NotNull(message = "validation.report.reason.invalid")
        @Pattern(regexp = "spam|offensive|broken-link|other", message = "validation.report.reason.invalid") String reason,
        @Size(max = 1000, message = "validation.report.comment.too-long") String comment) {
}

@Introspected
record ReportResolutionInput(
        @NotNull(message = "validation.resolution.invalid")
        @Pattern(regexp = "open|dismissed|actioned", message = "validation.resolution.invalid") String resolution,
        @Size(max = 1000, message = "validation.resolution.note.too-long") String note) {
}

@Introspected
record BookmarkStatusInput(
        @NotNull(message = "validation.bookmark-status.invalid")
        @Pattern(regexp = "active|hidden", message = "validation.bookmark-status.invalid") String status,
        @Size(max = 1000, message = "validation.bookmark-status.note.too-long") String note) {
}

record ReportResponse(UUID id, UUID bookmarkId, String reporter, String reason, String comment, String status,
                      Instant createdAt, String resolvedBy, Instant resolvedAt, String resolutionNote) {
    static ReportResponse from(Report report) {
        return new ReportResponse(report.id(), report.bookmarkId(), report.reporter(), report.reason(),
                report.comment(), report.status(), report.createdAt(), report.resolvedBy(), report.resolvedAt(),
                report.resolutionNote());
    }
}

record UserResponse(String username, String name, String email, List<String> roles) {
}

record AccountResponse(String username, Instant firstSeen, Instant lastSeen, String status, String blockedReason,
                       long bookmarkCount) {
}

record UserStatusInput(String status, String reason) {
}

record AuditResponse(UUID id, String actor, String action, String targetType, String targetId, Object detail,
                     Instant createdAt) {
}

record StatsTotals(long users, long bookmarks, long publicBookmarks, long hiddenBookmarks, long openReports) {
}

record DailyStat(String date, long bookmarksCreated, long activeUsers) {
}

record AdminStats(StatsTotals totals, List<DailyStat> daily, List<TagCount> topTags) {
}
