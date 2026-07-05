package dev.stackverse.backend;

import io.micronaut.http.HttpStatus;

import java.util.ArrayList;
import java.util.List;

record ProblemBody(String type, String title, int status, String detail, List<FieldErrorBody> errors) {
}

record FieldErrorBody(String field, String messageKey, String message) {
}

record FieldViolation(String field, String messageKey) {
}

final class ProblemException extends RuntimeException {
    final HttpStatus status;
    final String title;
    final String detail;
    final String detailKey;
    final List<FieldViolation> fields;

    ProblemException(HttpStatus status, String title, String detail, String detailKey, List<FieldViolation> fields) {
        super(detail == null || detail.isBlank() ? title : detail);
        this.status = status;
        this.title = title;
        this.detail = detail;
        this.detailKey = detailKey;
        this.fields = fields == null ? List.of() : List.copyOf(fields);
    }
}

final class Problems {
    private Problems() {
    }

    static ProblemException notFound() {
        return new ProblemException(HttpStatus.NOT_FOUND, "Not Found", null, null, List.of());
    }

    static ProblemException unauthorized(String detail) {
        return new ProblemException(HttpStatus.UNAUTHORIZED, "Unauthorized", detail, null, List.of());
    }

    static ProblemException forbidden(String detail) {
        return new ProblemException(HttpStatus.FORBIDDEN, "Forbidden", detail, null, List.of());
    }

    static ProblemException forbiddenKey(String key) {
        return new ProblemException(HttpStatus.FORBIDDEN, "Forbidden", null, key, List.of());
    }

    static ProblemException conflict(String detail) {
        return new ProblemException(HttpStatus.CONFLICT, "Conflict", detail, null, List.of());
    }

    static ProblemException conflictKey(String key) {
        return new ProblemException(HttpStatus.CONFLICT, "Conflict", null, key, List.of());
    }

    static ProblemException badRequest(String detail) {
        return new ProblemException(HttpStatus.BAD_REQUEST, "Bad Request", detail, null, List.of());
    }

    static ProblemException validation(List<FieldViolation> fields) {
        return new ProblemException(HttpStatus.BAD_REQUEST, "Bad Request", "Request validation failed.", null, fields);
    }
}

final class Validator {
    private final List<FieldViolation> fields = new ArrayList<>();

    void reject(String field, String messageKey) {
        fields.add(new FieldViolation(field, messageKey));
    }

    void check(boolean condition, String field, String messageKey) {
        if (!condition) {
            reject(field, messageKey);
        }
    }

    void throwIfInvalid() {
        if (!fields.isEmpty()) {
            throw Problems.validation(fields);
        }
    }
}
