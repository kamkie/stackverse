package dev.stackverse.backend.bookmark;

import dev.stackverse.backend.common.BadRequestProblem;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

public record BookmarkCursor(Instant createdAt, UUID id) {
    public String encode() {
        return Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString((createdAt + "|" + id).getBytes(StandardCharsets.UTF_8));
    }

    public static BookmarkCursor of(Bookmark bookmark) {
        return new BookmarkCursor(bookmark.getCreatedAt(), bookmark.getId());
    }

    public static BookmarkCursor decode(String cursor) {
        try {
            String decoded = new String(Base64.getUrlDecoder().decode(cursor), StandardCharsets.UTF_8);
            String[] parts = decoded.split("\\|", 2);
            if (parts.length != 2) {
                throw new IllegalArgumentException("missing cursor fields");
            }
            return new BookmarkCursor(Instant.parse(parts[0]), UUID.fromString(parts[1]));
        } catch (Exception exception) {
            throw new BadRequestProblem("The cursor is malformed or unresolvable.");
        }
    }
}
