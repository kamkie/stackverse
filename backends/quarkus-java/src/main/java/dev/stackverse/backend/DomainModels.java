package dev.stackverse.backend;

import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Base64;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
record Bookmark(
        UUID id,
        String owner,
        String url,
        String title,
        String notes,
        List<String> tags,
        String visibility,
        String status,
        Instant createdAt,
        Instant updatedAt) {
    boolean visibleTo(String caller) {
        return owner.equals(caller) || ("public".equals(visibility) && "active".equals(status));
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record Message(
        UUID id,
        String key,
        String language,
        String text,
        String description,
        Instant createdAt,
        Instant updatedAt) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
record Report(
        UUID id,
        UUID bookmarkId,
        String reporter,
        String reason,
        String comment,
        String status,
        String resolvedBy,
        Instant resolvedAt,
        String resolutionNote,
        Instant createdAt) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
record UserAccount(
        String username,
        Instant firstSeen,
        Instant lastSeen,
        String status,
        String blockedReason,
        long bookmarkCount) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
record AuditEntry(
        UUID id,
        String actor,
        String action,
        String targetType,
        String targetId,
        String detail,
        Instant createdAt) {}

record BookmarkInput(
        @NotBlank(message = "validation.url.required") @Size(max = 2000, message = "validation.url.invalid") @HttpUrl
                String url,
        @NotBlank(message = "validation.title.required") @Size(max = 200, message = "validation.title.too-long") String title,
        @Size(max = 4000, message = "validation.notes.too-long") String notes,
        @Size(max = 10, message = "validation.tags.too-many") List<
                                @Pattern(
                                        regexp = "^[a-z0-9-]{1,30}$",
                                        message = "validation.tag.invalid")
                                String>
                        tags,
        String visibility) {
    BookmarkInput {
        url = url == null ? "" : url.trim();
        title = title == null ? "" : title.trim();
        tags =
                tags == null
                        ? List.of()
                        : List.copyOf(
                                new LinkedHashSet<>(
                                        tags.stream()
                                                .map(
                                                        tag ->
                                                                tag == null
                                                                        ? ""
                                                                        : tag.trim()
                                                                                .toLowerCase(
                                                                                        Locale
                                                                                                .ROOT))
                                                .toList()));
        visibility = visibility == null ? "private" : visibility;
    }
}

record MessageInput(
        @NotBlank(message = "validation.message.key.invalid") @Size(max = 150, message = "validation.message.key.invalid") @Pattern(
                        regexp = "^[a-z0-9-]+(\\.[a-z0-9-]+)*$",
                        message = "validation.message.key.invalid")
                String key,
        @Pattern(regexp = "^[a-z]{2}$", message = "validation.message.language.invalid") String language,
        @NotEmpty(message = "validation.message.text.required") @Size(max = 2000, message = "validation.message.text.too-long") String text,
        @Size(max = 1000, message = "validation.message.description.too-long") String description) {
    MessageInput {
        key = key == null ? "" : key.trim();
        language = language == null ? "" : language.trim();
        text = text == null ? "" : text;
    }
}

record ReportInput(
        @Pattern(
                        regexp = "^(spam|offensive|broken-link|other)$",
                        message = "validation.report.reason.invalid")
                String reason,
        @Size(max = 1000, message = "validation.report.comment.too-long") String comment) {
    ReportInput {
        reason = reason == null ? "" : reason;
    }
}

record ResolutionInput(
        @Pattern(regexp = "^(open|dismissed|actioned)$", message = "validation.resolution.invalid")
                String resolution,
        @Size(max = 1000, message = "validation.resolution.note.too-long") String note) {
    ResolutionInput {
        resolution = resolution == null ? "" : resolution;
    }
}

record BookmarkStatusInput(
        @Pattern(regexp = "^(active|hidden)$", message = "validation.bookmark-status.invalid")
                String status,
        @Size(max = 1000, message = "validation.bookmark-status.note.too-long") String note) {
    BookmarkStatusInput {
        status = status == null ? "" : status;
    }
}

@ValidUserStatus
record UserStatusInput(String status, String reason) {
    UserStatusInput {
        status = status == null ? "" : status;
        reason = reason == null ? null : reason.trim();
    }
}

@JsonInclude(JsonInclude.Include.NON_NULL)
record BookmarkResponse(
        UUID id,
        String url,
        String title,
        String notes,
        List<String> tags,
        String visibility,
        String status,
        String owner,
        Instant createdAt,
        Instant updatedAt) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
record MessageResponse(
        UUID id,
        String key,
        String language,
        String text,
        String description,
        Instant createdAt,
        Instant updatedAt) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
record ReportResponse(
        UUID id,
        UUID bookmarkId,
        String reporter,
        String reason,
        String comment,
        String status,
        Instant createdAt,
        String resolvedBy,
        Instant resolvedAt,
        String resolutionNote) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
record UserAccountResponse(
        String username,
        Instant firstSeen,
        Instant lastSeen,
        String status,
        String blockedReason,
        long bookmarkCount) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
record AuditResponse(
        UUID id,
        String actor,
        String action,
        String targetType,
        String targetId,
        Map<String, Object> detail,
        Instant createdAt) {}

record PageResponse<T>(List<T> items, int page, int size, long totalItems, int totalPages) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
record CursorPage<T>(List<T> items, String nextCursor) {}

@JsonInclude(JsonInclude.Include.NON_NULL)
record MeResponse(String username, String name, String email, List<String> roles) {}

record TagCount(String tag, long count) {}

record TagsResponse(List<TagCount> tags) {}

record MessageBundleResponse(String language, Map<String, String> messages) {}

record StatusChange(String previous, Bookmark bookmark) {}

record DayCount(LocalDate date, long count) {}

record Page<T>(List<T> items, int page, int size, long totalItems) {}

record Cursor(Instant createdAt, UUID id) {
    String encode() {
        String raw = createdAt.toString() + "|" + id;
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(raw.getBytes(StandardCharsets.UTF_8));
    }

    static Cursor decode(String raw) {
        StackverseProblem malformed =
                StackverseProblem.badRequest("The cursor is malformed or unresolvable.");
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(raw), StandardCharsets.UTF_8);
            int separator = decoded.indexOf('|');
            if (separator < 0) {
                throw malformed;
            }
            return new Cursor(
                    Instant.parse(decoded.substring(0, separator)),
                    UUID.fromString(decoded.substring(separator + 1)));
        } catch (IllegalArgumentException | DateTimeParseException error) {
            throw malformed;
        }
    }
}
