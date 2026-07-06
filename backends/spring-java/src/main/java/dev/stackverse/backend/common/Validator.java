package dev.stackverse.backend.common;

import java.util.ArrayList;
import java.util.List;

/** Collects violations and throws once so all field errors are reported together. */
public class Validator {
    private final List<FieldViolation> violations = new ArrayList<>();

    public void reject(String field, String messageKey) {
        violations.add(new FieldViolation(field, messageKey));
    }

    public void check(boolean condition, String field, String messageKey) {
        if (!condition) {
            reject(field, messageKey);
        }
    }

    public void throwIfInvalid() {
        if (!violations.isEmpty()) {
            throw new ValidationProblem(violations);
        }
    }
}
