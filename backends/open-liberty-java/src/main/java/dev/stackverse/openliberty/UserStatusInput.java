package dev.stackverse.openliberty;

/** JSON request DTO for administrator account-state changes. */
public record UserStatusInput(String status, String reason) {}
