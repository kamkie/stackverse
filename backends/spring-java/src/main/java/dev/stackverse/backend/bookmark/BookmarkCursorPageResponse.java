package dev.stackverse.backend.bookmark;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record BookmarkCursorPageResponse(List<BookmarkResponse> items, String nextCursor) {
}
