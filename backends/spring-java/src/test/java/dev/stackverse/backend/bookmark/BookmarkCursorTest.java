package dev.stackverse.backend.bookmark;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.stackverse.backend.common.BadRequestProblem;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class BookmarkCursorTest {
    @Test
    void roundTripsOpaqueCursor() {
        BookmarkCursor cursor = new BookmarkCursor(Instant.parse("2026-07-01T12:34:56.123456Z"), UUID.randomUUID());

        assertThat(BookmarkCursor.decode(cursor.encode())).isEqualTo(cursor);
    }

    @Test
    void rejectsMalformedCursor() {
        assertThatThrownBy(() -> BookmarkCursor.decode("not-a-valid-cursor"))
            .isInstanceOf(BadRequestProblem.class)
            .hasMessageContaining("cursor");
    }
}
