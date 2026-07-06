package dev.stackverse.backend.bookmark;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookmarkResponse(
    UUID id,
    String url,
    String title,
    String notes,
    List<String> tags,
    Visibility visibility,
    BookmarkStatus status,
    String owner,
    Instant createdAt,
    Instant updatedAt
) {
    public static BookmarkResponse of(Bookmark bookmark) {
        return new BookmarkResponse(
            bookmark.getId(),
            bookmark.getUrl(),
            bookmark.getTitle(),
            bookmark.getNotes(),
            bookmark.getTags().stream().sorted(Comparator.naturalOrder()).toList(),
            bookmark.getVisibility(),
            bookmark.getStatus(),
            bookmark.getOwner(),
            bookmark.getCreatedAt(),
            bookmark.getUpdatedAt()
        );
    }
}
