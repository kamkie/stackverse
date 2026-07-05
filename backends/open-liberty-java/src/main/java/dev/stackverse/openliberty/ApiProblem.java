package dev.stackverse.openliberty;

import java.util.ArrayList;
import java.util.List;

class ApiProblem extends RuntimeException {
  final int status;
  final String title;
  final String detail;
  final String detailKey;

  ApiProblem(int status, String title, String detail) {
    this(status, title, detail, null);
  }

  ApiProblem(int status, String title, String detail, String detailKey) {
    super(detail == null ? title : detail);
    this.status = status;
    this.title = title;
    this.detail = detail;
    this.detailKey = detailKey;
  }

  static ApiProblem badRequest(String detail) {
    return new ApiProblem(400, "Bad Request", detail);
  }

  static ApiProblem unauthorized() {
    return new ApiProblem(401, "Unauthorized", "Authentication is required.");
  }

  static ApiProblem unauthorized(String detail) {
    return new ApiProblem(401, "Unauthorized", detail);
  }

  static ApiProblem forbidden(String detail) {
    return new ApiProblem(403, "Forbidden", detail);
  }

  static ApiProblem notFound() {
    return new ApiProblem(404, "Not Found", null);
  }

  static ApiProblem conflict(String detail) {
    return new ApiProblem(409, "Conflict", detail);
  }
}

record FieldViolation(String field, String messageKey) {}

class ValidationProblem extends RuntimeException {
  final List<FieldViolation> violations;

  ValidationProblem(List<FieldViolation> violations) {
    super("Validation failed");
    this.violations = List.copyOf(violations);
  }
}

class Validator {
  private final List<FieldViolation> violations = new ArrayList<>();

  void reject(String field, String messageKey) {
    violations.add(new FieldViolation(field, messageKey));
  }

  void check(boolean condition, String field, String messageKey) {
    if (!condition) {
      reject(field, messageKey);
    }
  }

  void throwIfInvalid() {
    if (!violations.isEmpty()) {
      throw new ValidationProblem(violations);
    }
  }
}
