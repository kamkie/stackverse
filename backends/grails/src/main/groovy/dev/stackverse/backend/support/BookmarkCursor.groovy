package dev.stackverse.backend.support

import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.Base64

class BookmarkCursor {
    final Instant createdAt
    final UUID id

    BookmarkCursor(Instant createdAt, UUID id) {
        this.createdAt = createdAt
        this.id = id
    }

    String encode() {
        Base64.urlEncoder.withoutPadding().encodeToString("${createdAt}|${id}".getBytes(StandardCharsets.UTF_8))
    }

    static BookmarkCursor decode(String cursor) {
        try {
            String decoded = new String(Base64.urlDecoder.decode(cursor), StandardCharsets.UTF_8)
            List parts = decoded.split("\\|", 2)
            if (parts.size() != 2) {
                throw new IllegalArgumentException("missing parts")
            }
            return new BookmarkCursor(Instant.parse(parts[0] as String), UUID.fromString(parts[1] as String))
        } catch (Exception ignored) {
            throw ApiError.badRequest("The cursor is malformed or unresolvable.")
        }
    }
}
