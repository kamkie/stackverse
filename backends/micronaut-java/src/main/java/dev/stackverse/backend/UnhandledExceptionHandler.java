package dev.stackverse.backend;

import io.micronaut.context.annotation.Requires;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.server.exceptions.ExceptionHandler;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

@Produces(MediaType.APPLICATION_JSON)
@Singleton
@Requires(classes = {Exception.class, ExceptionHandler.class})
final class UnhandledExceptionHandler implements ExceptionHandler<Exception, MutableHttpResponse<ProblemBody>> {
    private static final Logger LOG = LoggerFactory.getLogger(UnhandledExceptionHandler.class);

    @Override
    public MutableHttpResponse<ProblemBody> handle(HttpRequest request, Exception exception) {
        LOG.error("Unhandled error serving request", exception);
        ProblemBody body = new ProblemBody("about:blank", "Internal Server Error", 500, null, null);
        return HttpResponse.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .contentType(MediaType.of("application/problem+json"))
                .body(body);
    }
}
