package dev.stackverse.backend.bookmark;

import java.util.List;

public record BookmarkListQuery(List<String> tags, String q, Visibility visibility) {
}
