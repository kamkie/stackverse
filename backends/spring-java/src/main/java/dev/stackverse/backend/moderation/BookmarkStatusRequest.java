package dev.stackverse.backend.moderation;

import dev.stackverse.backend.bookmark.BookmarkStatus;

public record BookmarkStatusRequest(BookmarkStatus status, String note) {
}
