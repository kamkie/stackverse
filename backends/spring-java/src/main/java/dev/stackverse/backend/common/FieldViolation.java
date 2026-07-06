package dev.stackverse.backend.common;

/** One field-level validation failure; the message is localized when rendered. */
public record FieldViolation(String field, String messageKey) {
}
