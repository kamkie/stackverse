package dev.stackverse.backend.bookmark;

import java.util.List;

public record BookmarkRequest(
    String url,
    String title,
    String notes,
    List<String> tags,
    Visibility visibility
) {
}
