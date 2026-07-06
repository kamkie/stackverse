package dev.stackverse.backend.common;

import java.util.List;

/** Validation failure carrying field-level errors (SPEC rules 5 and 11). */
public class ValidationProblem extends RuntimeException {
    private final List<FieldViolation> violations;

    public ValidationProblem(List<FieldViolation> violations) {
        super("Validation failed");
        this.violations = List.copyOf(violations);
    }

    public List<FieldViolation> getViolations() {
        return violations;
    }
}
