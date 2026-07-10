package dev.stackverse.backend;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.http.server.exceptions.JacksonExceptionHandler;
import jakarta.inject.Singleton;
import tools.jackson.core.JacksonException;

@Produces(MediaType.APPLICATION_JSON)
@Singleton
@Replaces(JacksonExceptionHandler.class)
final class JacksonProblemHandler
        implements ExceptionHandler<JacksonException, MutableHttpResponse<ProblemBody>> {
    private final ProblemHandler problems;

    JacksonProblemHandler(ProblemHandler problems) {
        this.problems = problems;
    }

    @Override
    public MutableHttpResponse<ProblemBody> handle(
            HttpRequest request, JacksonException exception) {
        return problems.response(request, Problems.badRequest("Malformed request body."));
    }
}
