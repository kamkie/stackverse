package dev.stackverse.backend;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;
import io.micronaut.core.annotation.AnnotationValue;
import io.micronaut.core.annotation.Introspected;
import io.micronaut.validation.validator.constraints.ConstraintValidator;
import io.micronaut.validation.validator.constraints.ConstraintValidatorContext;

import java.lang.annotation.Documented;
import java.lang.annotation.Retention;
import java.lang.annotation.Target;
import java.util.List;

import static java.lang.annotation.ElementType.FIELD;
import static java.lang.annotation.ElementType.METHOD;
import static java.lang.annotation.ElementType.PARAMETER;
import static java.lang.annotation.ElementType.RECORD_COMPONENT;
import static java.lang.annotation.RetentionPolicy.RUNTIME;

public final class InputConstraints {
    private InputConstraints() {
    }

    @Introspected
    public static final class BookmarkUrlValidator implements ConstraintValidator<BookmarkUrl, String> {
        @Override
        public boolean isValid(String value, AnnotationValue<BookmarkUrl> annotation,
                               ConstraintValidatorContext context) {
            String url = WebSupport.trim(value);
            if (url.isBlank()) {
                context.messageTemplate("validation.url.required");
                return false;
            }
            if (WebSupport.length(url) > 2000 || !WebSupport.isHttpUrl(url)) {
                context.messageTemplate("validation.url.invalid");
                return false;
            }
            return true;
        }
    }

    @Introspected
    public static final class BookmarkTitleValidator implements ConstraintValidator<BookmarkTitle, String> {
        @Override
        public boolean isValid(String value, AnnotationValue<BookmarkTitle> annotation,
                               ConstraintValidatorContext context) {
            String title = WebSupport.trim(value);
            if (title.isBlank()) {
                context.messageTemplate("validation.title.required");
                return false;
            }
            if (WebSupport.length(title) > 200) {
                context.messageTemplate("validation.title.too-long");
                return false;
            }
            return true;
        }
    }

    @Introspected
    public static final class BookmarkTagCountValidator implements ConstraintValidator<BookmarkTagCount, List<String>> {
        @Override
        public boolean isValid(List<String> value, AnnotationValue<BookmarkTagCount> annotation,
                               ConstraintValidatorContext context) {
            return WebSupport.normalizeTags(value).size() <= 10;
        }
    }

    @Introspected
    public static final class BookmarkTagSyntaxValidator implements ConstraintValidator<BookmarkTagSyntax, List<String>> {
        @Override
        public boolean isValid(List<String> value, AnnotationValue<BookmarkTagSyntax> annotation,
                               ConstraintValidatorContext context) {
            return WebSupport.normalizeTags(value).stream()
                    .allMatch(tag -> WebSupport.TAG_PATTERN.matcher(tag).matches());
        }
    }

    @Introspected
    public static final class MessageKeyValidator implements ConstraintValidator<MessageKey, String> {
        @Override
        public boolean isValid(String value, AnnotationValue<MessageKey> annotation,
                               ConstraintValidatorContext context) {
            String key = WebSupport.trim(value);
            return WebSupport.KEY_PATTERN.matcher(key).matches() && WebSupport.length(key) <= 150;
        }
    }

    @Introspected
    public static final class MessageLanguageValidator implements ConstraintValidator<MessageLanguage, String> {
        @Override
        public boolean isValid(String value, AnnotationValue<MessageLanguage> annotation,
                               ConstraintValidatorContext context) {
            return WebSupport.LANGUAGE_PATTERN.matcher(WebSupport.trim(value)).matches();
        }
    }
}

@Documented
@Retention(RUNTIME)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT})
@Constraint(validatedBy = InputConstraints.BookmarkUrlValidator.class)
@interface BookmarkUrl {
    String message() default "validation.url.invalid";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

@Documented
@Retention(RUNTIME)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT})
@Constraint(validatedBy = InputConstraints.BookmarkTitleValidator.class)
@interface BookmarkTitle {
    String message() default "validation.title.required";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

@Documented
@Retention(RUNTIME)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT})
@Constraint(validatedBy = InputConstraints.BookmarkTagCountValidator.class)
@interface BookmarkTagCount {
    String message() default "validation.tags.too-many";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

@Documented
@Retention(RUNTIME)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT})
@Constraint(validatedBy = InputConstraints.BookmarkTagSyntaxValidator.class)
@interface BookmarkTagSyntax {
    String message() default "validation.tag.invalid";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

@Documented
@Retention(RUNTIME)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT})
@Constraint(validatedBy = InputConstraints.MessageKeyValidator.class)
@interface MessageKey {
    String message() default "validation.message.key.invalid";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}

@Documented
@Retention(RUNTIME)
@Target({FIELD, METHOD, PARAMETER, RECORD_COMPONENT})
@Constraint(validatedBy = InputConstraints.MessageLanguageValidator.class)
@interface MessageLanguage {
    String message() default "validation.message.language.invalid";

    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};
}
