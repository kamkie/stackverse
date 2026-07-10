package dev.stackverse.openliberty;

import java.util.List;
import java.util.Map;

/** Typed wire models kept separate from JDBC and request DTOs. */
final class ApiModels {
    private ApiModels() {}

    record Bookmark(
            String id,
            String url,
            String title,
            String notes,
            List<String> tags,
            String visibility,
            String status,
            String owner,
            String createdAt,
            String updatedAt) {}

    record Message(
            String id,
            String key,
            String language,
            String text,
            String description,
            String createdAt,
            String updatedAt) {}

    record Report(
            String id,
            String bookmarkId,
            String reporter,
            String reason,
            String comment,
            String status,
            String createdAt,
            String resolvedBy,
            String resolvedAt,
            String resolutionNote) {}

    record UserAccount(
            String username,
            String firstSeen,
            String lastSeen,
            String status,
            String blockedReason,
            long bookmarkCount) {}

    record AuditEntry(
            String id,
            String actor,
            String action,
            String targetType,
            String targetId,
            Map<String, Object> detail,
            String createdAt) {}

    record Page(List<?> items, int page, int size, long totalItems, long totalPages) {}

    record BookmarkCursorPage(List<Bookmark> items, String nextCursor) {}

    record MessageBundle(String language, Map<String, String> messages) {}

    record TagCount(String tag, int count) {}

    record Tags(List<TagCount> tags) {}

    record AdminTotals(
            long users,
            long bookmarks,
            long publicBookmarks,
            long hiddenBookmarks,
            long openReports) {}

    record DailyStat(String date, int bookmarksCreated, int activeUsers) {}

    record AdminStats(AdminTotals totals, List<DailyStat> daily, List<TagCount> topTags) {}

    record User(String username, String name, String email, List<String> roles) {}

    record FieldError(String field, String messageKey, String message) {}

    record Problem(String type, String title, int status, String detail, List<FieldError> errors) {}
}
