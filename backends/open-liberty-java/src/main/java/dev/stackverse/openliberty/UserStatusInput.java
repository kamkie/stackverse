package dev.stackverse.openliberty;

import jakarta.validation.constraints.Size;

/** JSON request DTO for administrator account-state changes. */
public record UserStatusInput(
        String status,
        @Size(max = 1000, message = "{validation.block.reason.too-long}") String reason) {}
