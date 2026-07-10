package dev.stackverse.openliberty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** JSON request DTO for moderator bookmark-state changes. */
public record BookmarkStatusInput(
        @NotBlank(message = "{validation.bookmark-status.invalid}") @Pattern(regexp = "active|hidden", message = "{validation.bookmark-status.invalid}") String status,
        @Size(max = 1000, message = "{validation.bookmark-status.note.too-long}") String note) {}
