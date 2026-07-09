package dev.stackverse.backend;

import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validator;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Singleton
final class InputValidator {
    private static final Map<String, Integer> FIELD_ORDER = Map.ofEntries(
            Map.entry("url", 0),
            Map.entry("title", 1),
            Map.entry("notes", 2),
            Map.entry("tags", 3),
            Map.entry("key", 4),
            Map.entry("language", 5),
            Map.entry("text", 6),
            Map.entry("description", 7),
            Map.entry("reason", 8),
            Map.entry("comment", 9),
            Map.entry("resolution", 10),
            Map.entry("note", 11),
            Map.entry("status", 12)
    );
    private static final Map<String, Integer> MESSAGE_ORDER = Map.of(
            "validation.tags.too-many", 0,
            "validation.tag.invalid", 1
    );

    private final Validator validator;

    InputValidator(Validator validator) {
        this.validator = validator;
    }

    void validate(BookmarkInput input) {
        validateObject(input == null ? new BookmarkInput(null, null, null, null, null) : input);
    }

    void validate(MessageInput input) {
        validateObject(input == null ? new MessageInput(null, null, null, null) : input);
    }

    void validate(ReportInput input) {
        validateObject(input == null ? new ReportInput(null, null) : input);
    }

    void validate(ReportResolutionInput input) {
        validateObject(input == null ? new ReportResolutionInput(null, null) : input);
    }

    void validate(BookmarkStatusInput input) {
        validateObject(input == null ? new BookmarkStatusInput(null, null) : input);
    }

    private <T> void validateObject(T input) {
        Set<ConstraintViolation<T>> violations = validator.validate(input);
        if (violations.isEmpty()) {
            return;
        }
        List<FieldViolation> fields = violations.stream()
                .map(violation -> new FieldViolation(fieldName(violation), messageKey(violation)))
                .distinct()
                .sorted(Comparator
                        .comparingInt((FieldViolation field) -> FIELD_ORDER.getOrDefault(field.field(), Integer.MAX_VALUE))
                        .thenComparingInt(field -> MESSAGE_ORDER.getOrDefault(field.messageKey(), 0)))
                .toList();
        throw Problems.validation(fields);
    }

    private String fieldName(ConstraintViolation<?> violation) {
        String path = violation.getPropertyPath().toString();
        int separator = path.lastIndexOf('.');
        String field = separator < 0 ? path : path.substring(separator + 1);
        return field.replaceFirst("\\[.*$", "");
    }

    private String messageKey(ConstraintViolation<?> violation) {
        String template = violation.getMessageTemplate();
        if (template.startsWith("{") && template.endsWith("}")) {
            return template.substring(1, template.length() - 1);
        }
        return template;
    }
}
