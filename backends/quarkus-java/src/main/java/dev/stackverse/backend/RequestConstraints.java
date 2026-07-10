package dev.stackverse.backend;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Documented
@Constraint(validatedBy = HttpUrlValidator.class)
@Target({
    ElementType.FIELD,
    ElementType.PARAMETER,
    ElementType.RECORD_COMPONENT,
    ElementType.TYPE_USE
})
@Retention(RetentionPolicy.RUNTIME)
@interface HttpUrl {
    String message() default "validation.url.invalid";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

@Documented
@Constraint(validatedBy = UserStatusValidator.class)
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@interface ValidUserStatus {
    String message() default "validation.block.reason.required";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
