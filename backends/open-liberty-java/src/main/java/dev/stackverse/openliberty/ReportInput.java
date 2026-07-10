package dev.stackverse.openliberty;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** JSON request DTO for creating and editing reports. */
public record ReportInput(
        @NotBlank(message = "{validation.report.reason.invalid}") @Pattern(
                        regexp = "spam|offensive|broken-link|other",
                        message = "{validation.report.reason.invalid}")
                String reason,
        @Size(max = 1000, message = "{validation.report.comment.too-long}") String comment) {}
