package dev.stackverse.openliberty;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.json.bind.JsonbException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.ProcessingException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Provider
@RequestScoped
public class ProblemMapper implements ExceptionMapper<Throwable> {
    @Inject MessageCatalog messages;
    @Inject EventLogger log;

    @Context UriInfo uriInfo;

    @Context HttpHeaders headers;

    @Context HttpServletRequest request;

    @Override
    public Response toResponse(Throwable throwable) {
        Throwable ex = unwrap(throwable);
        if (ex instanceof ValidationProblem validation) {
            Caller caller = (Caller) request.getAttribute(AuthFilter.CALLER_ATTRIBUTE);
            Map<String, Object> fields = new LinkedHashMap<>();
            if (caller != null) {
                fields.put("actor", caller.username());
            }
            fields.put("error_code", "validation_failed");
            log.event(
                    "info",
                    "input_validation_failed",
                    "failure",
                    "Input validation failed",
                    fields);
            String language =
                    messages.resolveLanguage(
                            messages.firstParam(uriInfo.getQueryParameters().get("lang")),
                            headers.getHeaderString("Accept-Language"));
            List<ApiModels.FieldError> errors = new ArrayList<>();
            for (FieldViolation violation : validation.violations) {
                errors.add(
                        new ApiModels.FieldError(
                                violation.field(),
                                violation.messageKey(),
                                messages.localize(violation.messageKey(), language)));
            }
            return withRouteHeaders(
                    JsonSupport.problem(400, "Bad Request", "Validation failed", errors));
        }
        if (ex instanceof ApiProblem problem) {
            String detail = problem.detail;
            if (problem.detailKey != null) {
                String language =
                        messages.resolveLanguage(
                                messages.firstParam(uriInfo.getQueryParameters().get("lang")),
                                headers.getHeaderString("Accept-Language"));
                detail = messages.localize(problem.detailKey, language);
            }
            return withRouteHeaders(
                    JsonSupport.problem(problem.status, problem.title, detail, null));
        }
        if (ex instanceof NotAllowedException) {
            return withRouteHeaders(JsonSupport.problem(405, "Method Not Allowed", null, null));
        }
        if (ex instanceof NotFoundException) {
            return withRouteHeaders(JsonSupport.problem(404, "Not Found", null, null));
        }
        if (ex instanceof ProcessingException || ex instanceof JsonbException) {
            return withRouteHeaders(
                    JsonSupport.problem(400, "Bad Request", "Malformed JSON request body.", null));
        }
        if (ex instanceof WebApplicationException web) {
            int status = web.getResponse().getStatus();
            return withRouteHeaders(
                    JsonSupport.problem(
                            status,
                            Response.Status.fromStatusCode(status).getReasonPhrase(),
                            null,
                            null));
        }
        if (EventLogger.causedBySqlFailure(ex)) {
            log.dependencyFailure("postgres", ex, RequestTimingFilter.elapsedMillis(request));
        } else {
            log.error(
                    "request_failed",
                    "failure",
                    "Unhandled request failure",
                    ex,
                    Map.of("error_code", EventLogger.errorCode(ex)));
        }
        return withRouteHeaders(
                JsonSupport.problem(
                        500, "Internal Server Error", "Unexpected server error.", null));
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof RuntimeException runtime
                && runtime.getCause() instanceof ApiProblem) {
            return runtime.getCause();
        }
        return throwable;
    }

    private Response withRouteHeaders(Response response) {
        if (DeprecationHeaders.isDeprecatedV1Bookmarks(request.getMethod(), uriInfo.getPath())) {
            return DeprecationHeaders.addV1BookmarkHeaders(response);
        }
        return response;
    }
}
