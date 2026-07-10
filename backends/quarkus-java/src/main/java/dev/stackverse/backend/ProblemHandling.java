package dev.stackverse.backend;

import io.quarkus.security.AuthenticationFailedException;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.annotation.Priority;
import jakarta.inject.Inject;
import jakarta.validation.ConstraintViolation;
import jakarta.validation.ConstraintViolationException;
import jakarta.validation.ElementKind;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.ExceptionMapper;
import jakarta.ws.rs.ext.Provider;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jboss.logging.Logger;

record FieldViolation(String field, String messageKey) {}

final class StackverseProblem extends RuntimeException {
    final int status;
    final String title;
    final String detail;
    final String detailKey;
    final List<FieldViolation> fields;

    StackverseProblem(
            int status,
            String title,
            String detail,
            String detailKey,
            List<FieldViolation> fields) {
        super(detail == null ? title : detail);
        this.status = status;
        this.title = title;
        this.detail = detail;
        this.detailKey = detailKey;
        this.fields = fields == null ? List.of() : fields;
    }

    static StackverseProblem notFound() {
        return new StackverseProblem(404, "Not Found", null, null, null);
    }

    static StackverseProblem unauthorized(String detail) {
        return new StackverseProblem(401, "Unauthorized", detail, null, null);
    }

    static StackverseProblem forbidden(String detail) {
        return new StackverseProblem(403, "Forbidden", detail, null, null);
    }

    static StackverseProblem forbiddenKey(String key) {
        return new StackverseProblem(403, "Forbidden", null, key, null);
    }

    static StackverseProblem conflict(String detail) {
        return new StackverseProblem(409, "Conflict", detail, null, null);
    }

    static StackverseProblem conflictKey(String key) {
        return new StackverseProblem(409, "Conflict", null, key, null);
    }

    static StackverseProblem badRequest(String detail) {
        return new StackverseProblem(400, "Bad Request", detail, null, null);
    }

    static StackverseProblem validation(List<FieldViolation> fields) {
        return new StackverseProblem(
                400, "Bad Request", "Request validation failed.", null, fields);
    }

    Response response(Localizer localizer, UriInfo uriInfo, HttpHeaders headers) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("type", "about:blank");
        body.put("title", title);
        body.put("status", status);
        String language = null;
        String resolvedDetail = detail;
        if (detailKey != null) {
            language = localizer.resolveLanguage(uriInfo, headers);
            resolvedDetail = localizer.localize(detailKey, language);
        }
        ServiceSupport.putIfPresent(body, "detail", resolvedDetail);
        if (!fields.isEmpty()) {
            if (language == null) {
                language = localizer.resolveLanguage(uriInfo, headers);
            }
            Set<String> messageKeys = new LinkedHashSet<>();
            for (FieldViolation field : fields) {
                messageKeys.add(field.messageKey());
            }
            Map<String, String> messages = localizer.localizeAll(messageKeys, language);
            List<Map<String, Object>> errors = new ArrayList<>();
            for (FieldViolation field : fields) {
                Map<String, Object> item = new LinkedHashMap<>();
                item.put("field", field.field());
                item.put("messageKey", field.messageKey());
                item.put("message", messages.getOrDefault(field.messageKey(), field.messageKey()));
                errors.add(item);
            }
            body.put("errors", errors);
        }
        return Response.status(status).type("application/problem+json").entity(body).build();
    }
}

final class Validator {
    private final List<FieldViolation> fields = new ArrayList<>();

    void reject(String field, String messageKey) {
        fields.add(new FieldViolation(field, messageKey));
    }

    void check(boolean condition, String field, String messageKey) {
        if (!condition) {
            reject(field, messageKey);
        }
    }

    void throwIfInvalid() {
        if (!fields.isEmpty()) {
            throw StackverseProblem.validation(List.copyOf(fields));
        }
    }
}

final class ResponseContracts {
    private ResponseContracts() {}

    static Response routeHeaders(
            ContainerRequestContext request, UriInfo uriInfo, Response response) {
        if (request == null || !"GET".equals(request.getMethod())) {
            return response;
        }
        String requestPath = request == null ? null : request.getUriInfo().getPath();
        String uriPath = uriInfo == null ? null : uriInfo.getPath();
        if (isV1BookmarksPath(requestPath) || isV1BookmarksPath(uriPath)) {
            return ServiceSupport.v1BookmarksDeprecationHeaders(response);
        }
        return response;
    }

    private static boolean isV1BookmarksPath(String path) {
        return "api/v1/bookmarks".equals(path) || "/api/v1/bookmarks".equals(path);
    }
}

@Provider
@Priority(1)
class AuthenticationFailedMapper implements ExceptionMapper<AuthenticationFailedException> {
    private static final Logger LOG = Logger.getLogger(AuthenticationFailedMapper.class);

    private final Localizer localizer;

    @Context UriInfo uriInfo;

    @Context HttpHeaders headers;

    @Context ContainerRequestContext request;

    @Inject
    AuthenticationFailedMapper(Localizer localizer) {
        this.localizer = localizer;
    }

    @Override
    public Response toResponse(AuthenticationFailedException exception) {
        StackverseLog.event(
                LOG,
                Logger.Level.INFO,
                "jwt_validation_failed",
                "failure",
                "Rejected a bearer token",
                Map.of("error_code", "invalid_token"));
        Response response =
                StackverseProblem.unauthorized("Missing or invalid bearer token.")
                        .response(localizer, uriInfo, headers);
        return ResponseContracts.routeHeaders(request, uriInfo, response);
    }
}

@Provider
@Priority(0)
class QuarkusUnauthorizedMapper
        implements ExceptionMapper<io.quarkus.security.UnauthorizedException> {
    private static final Logger LOG = Logger.getLogger(QuarkusUnauthorizedMapper.class);

    private final Localizer localizer;

    @Context UriInfo uriInfo;

    @Context HttpHeaders headers;

    @Context ContainerRequestContext request;

    @Inject
    QuarkusUnauthorizedMapper(Localizer localizer) {
        this.localizer = localizer;
    }

    @Override
    public Response toResponse(io.quarkus.security.UnauthorizedException exception) {
        StackverseLog.event(
                LOG,
                Logger.Level.INFO,
                "jwt_validation_failed",
                "failure",
                "Rejected a bearer token",
                Map.of("error_code", "invalid_token"));
        Response response =
                StackverseProblem.unauthorized("Missing or invalid bearer token.")
                        .response(localizer, uriInfo, headers);
        return ResponseContracts.routeHeaders(request, uriInfo, response);
    }
}

@Provider
@Priority(0)
class QuarkusForbiddenMapper implements ExceptionMapper<io.quarkus.security.ForbiddenException> {
    private static final Logger LOG = Logger.getLogger(QuarkusForbiddenMapper.class);

    private final Localizer localizer;
    private final SecurityIdentity securityIdentity;

    @Context UriInfo uriInfo;

    @Context HttpHeaders headers;

    @Context ContainerRequestContext request;

    @Inject
    QuarkusForbiddenMapper(Localizer localizer, SecurityIdentity securityIdentity) {
        this.localizer = localizer;
        this.securityIdentity = securityIdentity;
    }

    @Override
    public Response toResponse(io.quarkus.security.ForbiddenException exception) {
        String actor =
                securityIdentity == null || securityIdentity.isAnonymous()
                        ? null
                        : securityIdentity.getPrincipal().getName();
        StackverseLog.event(
                LOG,
                Logger.Level.INFO,
                "authz_denied",
                "denied",
                "Denied a request lacking the required role",
                actor == null
                        ? Map.of("error_code", "insufficient_role")
                        : Map.of("actor", actor, "error_code", "insufficient_role"));
        Response response =
                StackverseProblem.forbidden("You do not have the role required for this operation.")
                        .response(localizer, uriInfo, headers);
        return ResponseContracts.routeHeaders(request, uriInfo, response);
    }
}

@Provider
@Priority(1)
class ConstraintViolationMapper implements ExceptionMapper<ConstraintViolationException> {
    private static final Logger LOG = Logger.getLogger(ConstraintViolationMapper.class);

    private final Localizer localizer;

    @Context UriInfo uriInfo;

    @Context HttpHeaders headers;

    @Context ContainerRequestContext request;

    @Inject
    ConstraintViolationMapper(Localizer localizer) {
        this.localizer = localizer;
    }

    @Override
    public Response toResponse(ConstraintViolationException exception) {
        List<FieldViolation> fields =
                exception.getConstraintViolations().stream()
                        .map(ConstraintViolationMapper::fieldViolation)
                        .sorted(
                                java.util.Comparator.comparing(FieldViolation::field)
                                        .thenComparing(FieldViolation::messageKey))
                        .toList();
        StackverseLog.event(
                LOG,
                Logger.Level.INFO,
                "input_validation_failed",
                "failure",
                "Request validation failed",
                Map.of(
                        "error_code",
                        "validation_failed",
                        "fields",
                        String.join(",", fields.stream().map(FieldViolation::field).toList())));
        Response response =
                StackverseProblem.validation(fields).response(localizer, uriInfo, headers);
        return ResponseContracts.routeHeaders(request, uriInfo, response);
    }

    private static FieldViolation fieldViolation(ConstraintViolation<?> violation) {
        String field = "body";
        for (var node : violation.getPropertyPath()) {
            if (node.getKind() == ElementKind.PROPERTY && node.getName() != null) {
                field = node.getName();
            }
        }
        return new FieldViolation(field, violation.getMessage());
    }
}

@Provider
class ProblemMapper implements ExceptionMapper<Throwable> {
    private static final Logger LOG = Logger.getLogger(ProblemMapper.class);

    private final Localizer localizer;

    @Context UriInfo uriInfo;

    @Context HttpHeaders headers;

    @Context ContainerRequestContext request;

    @Inject
    ProblemMapper(Localizer localizer) {
        this.localizer = localizer;
    }

    @Override
    public Response toResponse(Throwable throwable) {
        StackverseProblem problem = toProblem(throwable);
        if (!problem.fields.isEmpty()) {
            StackverseLog.event(
                    LOG,
                    Logger.Level.INFO,
                    "input_validation_failed",
                    "failure",
                    "Request validation failed",
                    Map.of(
                            "error_code",
                            "validation_failed",
                            "fields",
                            String.join(
                                    ",",
                                    problem.fields.stream().map(FieldViolation::field).toList())));
        }
        if (problem.status >= 500) {
            LOG.error("Unhandled error serving request", throwable);
        }
        return ResponseContracts.routeHeaders(
                request, uriInfo, problem.response(localizer, uriInfo, headers));
    }

    private StackverseProblem toProblem(Throwable throwable) {
        if (throwable instanceof StackverseProblem problem) {
            return problem;
        }
        if (throwable instanceof NotAuthorizedException) {
            StackverseLog.event(
                    LOG,
                    Logger.Level.INFO,
                    "jwt_validation_failed",
                    "failure",
                    "Rejected a bearer token",
                    Map.of("error_code", "invalid_token"));
            return StackverseProblem.unauthorized("Missing or invalid bearer token.");
        }
        if (throwable instanceof ForbiddenException) {
            return StackverseProblem.forbidden(
                    "You do not have the role required for this operation.");
        }
        if (throwable instanceof NotFoundException) {
            return StackverseProblem.notFound();
        }
        if (throwable instanceof NotAllowedException) {
            return new StackverseProblem(405, "Method Not Allowed", null, null, null);
        }
        if (throwable instanceof BadRequestException) {
            return StackverseProblem.badRequest("Malformed request body.");
        }
        if (throwable instanceof WebApplicationException webApplicationException
                && webApplicationException.getResponse() != null
                && webApplicationException.getResponse().getStatus() >= 400
                && webApplicationException.getResponse().getStatus() < 500) {
            int status = webApplicationException.getResponse().getStatus();
            String title =
                    status == 401
                            ? "Unauthorized"
                            : status == 403
                                    ? "Forbidden"
                                    : status == 404
                                            ? "Not Found"
                                            : status == 405 ? "Method Not Allowed" : "Bad Request";
            return new StackverseProblem(status, title, null, null, null);
        }
        return new StackverseProblem(500, "Internal Server Error", null, null, null);
    }
}
