package dev.stackverse.backend.bookmark;

import java.util.List;

public record TagListResponse(List<TagCountResponse> tags) {
}
