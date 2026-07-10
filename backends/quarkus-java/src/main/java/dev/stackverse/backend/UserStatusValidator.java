package dev.stackverse.backend;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

public final class UserStatusValidator
        implements ConstraintValidator<ValidUserStatus, UserStatusInput> {
    @Override
    public boolean isValid(UserStatusInput input, ConstraintValidatorContext context) {
        if (input == null || !"blocked".equals(input.status())) {
            return true;
        }
        boolean valid = true;
        context.disableDefaultConstraintViolation();
        if (input.reason() == null || input.reason().isBlank()) {
            add(context, "validation.block.reason.required");
            valid = false;
        }
        if (input.reason() != null
                && input.reason().codePointCount(0, input.reason().length()) > 1000) {
            add(context, "validation.block.reason.too-long");
            valid = false;
        }
        return valid;
    }

    private static void add(ConstraintValidatorContext context, String key) {
        context.buildConstraintViolationWithTemplate(key)
                .addPropertyNode("reason")
                .addConstraintViolation();
    }
}
