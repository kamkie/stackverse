package dev.stackverse.backend.common;

import dev.stackverse.backend.message.MessageLocalizer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {
    private final MessageLocalizer localizer;
    private final org.slf4j.Logger log = LoggerFactory.getLogger(getClass());

    public ApiExceptionHandler(MessageLocalizer localizer) {
        this.localizer = localizer;
    }

    @ExceptionHandler(ApiProblem.class)
    public ProblemDetail handleApiProblem(ApiProblem exception, HttpServletRequest request) {
        ProblemDetail problem = ProblemDetail.forStatus(exception.getStatus());
        problem.setTitle(exception.getTitle());
        problem.setDetail(exception.getDetailKey() == null ? exception.getDetail() : localizer.localize(exception.getDetailKey(), request));
        return problem;
    }

    /** SPEC rules 5 + 11: field errors with a validation.* key and localized message. */
    @ExceptionHandler(ValidationProblem.class)
    public ProblemDetail handleValidation(ValidationProblem exception, HttpServletRequest request) {
        Logging.logEvent(
            log,
            Level.INFO,
            "input_validation_failed",
            "failure",
            "Request validation failed",
            "error_code", "validation_failed",
            "fields", exception.getViolations().stream().map(FieldViolation::field).reduce((a, b) -> a + "," + b).orElse("")
        );
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.BAD_REQUEST);
        problem.setTitle("Bad Request");
        problem.setDetail("Request validation failed.");
        problem.setProperty(
            "errors",
            exception.getViolations().stream()
                .map(v -> Map.of("field", v.field(), "messageKey", v.messageKey(), "message", localizer.localize(v.messageKey(), request)))
                .toList()
        );
        return problem;
    }

    /** Method-security denials surface here, not always in the filter chain. */
    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException exception) {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        Logging.logEvent(
            log,
            Level.INFO,
            "authz_denied",
            "denied",
            "Denied a request lacking the required role",
            "actor", authentication == null ? null : authentication.getName()
        );
        ProblemDetail problem = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);
        problem.setTitle("Forbidden");
        problem.setDetail("You do not have the role required for this operation.");
        return problem;
    }
}
