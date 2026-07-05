package dev.stackverse.backend;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

@Produces(MediaType.APPLICATION_JSON)
@Singleton
@Requires(classes = {ProblemException.class, ExceptionHandler.class})
final class ProblemHandler implements ExceptionHandler<ProblemException, MutableHttpResponse<ProblemBody>> {
    private static final Logger LOG = LoggerFactory.getLogger(ProblemHandler.class);

    private final MessageCatalog messages;

    ProblemHandler(MessageCatalog messages) {
        this.messages = messages;
    }

    @Override
    public MutableHttpResponse<ProblemBody> handle(HttpRequest request, ProblemException exception) {
        return response(request, exception);
    }

    MutableHttpResponse<ProblemBody> response(HttpRequest<?> request, ProblemException exception) {
        String detail = exception.detail;
        if (exception.detailKey != null) {
            detail = messages.localize(request, exception.detailKey);
        }
        List<FieldErrorBody> errors = null;
        if (!exception.fields.isEmpty()) {
            EventLog.info(LOG, "input_validation_failed", "failure", "Request validation failed");
            errors = exception.fields.stream()
                    .map(field -> new FieldErrorBody(field.field(), field.messageKey(), messages.localize(request, field.messageKey())))
                    .toList();
        }
        ProblemBody body = new ProblemBody("about:blank", exception.title, exception.status.getCode(), detail, errors);
        return HttpResponse.status(exception.status).contentType(MediaType.of("application/problem+json")).body(body);
    }
}
