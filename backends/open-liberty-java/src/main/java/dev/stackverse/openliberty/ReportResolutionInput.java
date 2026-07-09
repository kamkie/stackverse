package dev.stackverse.openliberty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** JSON request DTO for moderator report decisions. */
public record ReportResolutionInput(
        @NotBlank(message = "{validation.resolution.invalid}") @Pattern(
                        regexp = "open|dismissed|actioned",
                        message = "{validation.resolution.invalid}")
                String resolution,
        @Size(max = 1000, message = "{validation.resolution.note.too-long}") String note) {}
