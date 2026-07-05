package dev.stackverse.backend;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class ModelsTest {
    private static final Instant CREATED = Instant.parse("2026-07-05T12:00:00Z");
    private static final Instant UPDATED = Instant.parse("2026-07-05T12:30:00Z");

    @Test
    void publicActiveBookmarksAreVisibleToNonOwners() {
        Bookmark bookmark = bookmark("alice", Models.PUBLIC, Models.ACTIVE);

        assertThat(bookmark.visibleTo("bob")).isTrue();
    }

    @Test
    void privateOrHiddenBookmarksAreOnlyVisibleToOwners() {
        assertThat(bookmark("alice", Models.PRIVATE, Models.ACTIVE).visibleTo("bob")).isFalse();
        assertThat(bookmark("alice", Models.PUBLIC, Models.HIDDEN).visibleTo("bob")).isFalse();
        assertThat(bookmark("alice", Models.PRIVATE, Models.HIDDEN).visibleTo("alice")).isTrue();
    }

    @Test
    void bookmarkResponsesExposeSortedTagsAndPreserveContractFields() {
        Bookmark bookmark = new Bookmark(
                UUID.fromString("00000000-0000-0000-0000-000000000123"),
                "alice",
                "https://example.test",
                "Example",
                "Notes",
                List.of("zulu", "alpha", "java"),
                Models.PUBLIC,
                Models.ACTIVE,
                CREATED,
                UPDATED
        );

        BookmarkResponse response = BookmarkResponse.from(bookmark);

        assertThat(response.tags()).containsExactly("alpha", "java", "zulu");
        assertThat(response.owner()).isEqualTo("alice");
        assertThat(response.visibility()).isEqualTo(Models.PUBLIC);
        assertThat(response.status()).isEqualTo(Models.ACTIVE);
        assertThat(response.createdAt()).isEqualTo(CREATED);
        assertThat(response.updatedAt()).isEqualTo(UPDATED);
    }

    private Bookmark bookmark(String owner, String visibility, String status) {
        return new Bookmark(UUID.randomUUID(), owner, "https://example.test", "Example", null, List.of(),
                visibility, status, CREATED, UPDATED);
    }
}
