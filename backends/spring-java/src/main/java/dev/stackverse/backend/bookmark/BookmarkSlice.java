package dev.stackverse.backend.bookmark;

import java.util.List;

public record BookmarkSlice(List<Bookmark> items, BookmarkCursor nextCursor) {
}
