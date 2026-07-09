package dev.stackverse.backend;

import io.micronaut.context.annotation.Requires;
import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.validation.exceptions.ConstraintExceptionHandler;
import jakarta.inject.Singleton;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

@Produces(MediaType.APPLICATION_JSON)
@Singleton
@Replaces(ConstraintExceptionHandler.class)
@Requires(classes = {ConstraintViolationException.class, ExceptionHandler.class})
final class ValidationExceptionHandler implements ExceptionHandler<ConstraintViolationException, MutableHttpResponse<ProblemBody>> {
    private static final Set<String> INPUT_FIELDS = Set.of(
            "url", "title", "notes", "tags", "key", "language", "text", "description",
            "reason", "comment", "resolution", "note", "status"
    );

    private final ProblemHandler problems;

    ValidationExceptionHandler(ProblemHandler problems) {
        this.problems = problems;
    }

    @Override
    public MutableHttpResponse<ProblemBody> handle(HttpRequest request, ConstraintViolationException exception) {
        Set<FieldViolation> fields = new LinkedHashSet<>();
        for (ConstraintViolation<?> violation : exception.getConstraintViolations()) {
            fields.add(new FieldViolation(fieldName(violation), messageKey(violation)));
        }
        return problems.response(request, Problems.validation(List.copyOf(fields)));
    }

    private String fieldName(ConstraintViolation<?> violation) {
        String[] segments = violation.getPropertyPath().toString().split("\\.");
        for (int i = segments.length - 1; i >= 0; i--) {
            String candidate = segments[i].replaceFirst("\\[.*$", "");
            if (INPUT_FIELDS.contains(candidate)) {
                return candidate;
            }
        }
        return segments.length == 0 ? "body" : segments[segments.length - 1];
    }

    private String messageKey(ConstraintViolation<?> violation) {
        String template = violation.getMessageTemplate();
        if (template.startsWith("{") && template.endsWith("}")) {
            return template.substring(1, template.length() - 1);
        }
        return template;
    }
}
