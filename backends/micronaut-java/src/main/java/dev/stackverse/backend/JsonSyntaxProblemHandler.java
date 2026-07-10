package dev.stackverse.backend;

import io.micronaut.context.annotation.Replaces;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import io.micronaut.http.server.exceptions.JsonExceptionHandler;
import io.micronaut.json.JsonSyntaxException;
import jakarta.inject.Singleton;

@Produces(MediaType.APPLICATION_JSON)
@Singleton
@Replaces(JsonExceptionHandler.class)
final class JsonSyntaxProblemHandler
        implements ExceptionHandler<JsonSyntaxException, MutableHttpResponse<ProblemBody>> {
    private final ProblemHandler problems;

    JsonSyntaxProblemHandler(ProblemHandler problems) {
        this.problems = problems;
    }

    @Override
    public MutableHttpResponse<ProblemBody> handle(
            HttpRequest request, JsonSyntaxException exception) {
        return problems.response(request, Problems.badRequest("Malformed request body."));
    }
}
