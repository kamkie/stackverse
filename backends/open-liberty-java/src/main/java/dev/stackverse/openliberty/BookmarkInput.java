package dev.stackverse.openliberty;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.LinkedHashSet;
import java.util.List;

/** JSON request DTO for bookmark creation and replacement. */
public record BookmarkInput(
        @AbsoluteHttpUrl String url,
        @NotBlank(message = "{validation.title.required}") @Size(max = 200, message = "{validation.title.too-long}") String title,
        @Size(max = 4000, message = "{validation.notes.too-long}") String notes,
        @Size(max = 10, message = "{validation.tags.too-many}") List<
                                @Valid @Pattern(
                                        regexp = "^[a-z0-9-]{1,30}$",
                                        message = "{validation.tag.invalid}")
                                String>
                        tags,
        String visibility) {

    public BookmarkInput {
        url = url == null ? "" : url.trim();
        title = title == null ? "" : title.trim();
        tags =
                tags == null
                        ? List.of()
                        : new LinkedHashSet<>(
                                        tags.stream()
                                                .map(
                                                        tag ->
                                                                tag == null
                                                                        ? ""
                                                                        : tag.trim().toLowerCase())
                                                .toList())
                                .stream().toList();
        visibility = visibility == null ? "private" : visibility;
    }
}
