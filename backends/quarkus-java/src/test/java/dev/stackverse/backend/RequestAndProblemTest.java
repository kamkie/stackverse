package dev.stackverse.backend;

import static dev.stackverse.backend.PostgresTestSupport.authorization;
import static dev.stackverse.backend.PostgresTestSupport.identity;
import static dev.stackverse.backend.PostgresTestSupport.request;
import static dev.stackverse.backend.PostgresTestSupport.routeRequest;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import jakarta.validation.Validation;
import jakarta.ws.rs.BadRequestException;
import jakarta.ws.rs.ForbiddenException;
import jakarta.ws.rs.NotAllowedException;
import jakarta.ws.rs.NotAuthorizedException;
import jakarta.ws.rs.NotFoundException;
import jakarta.ws.rs.WebApplicationException;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import java.lang.reflect.Proxy;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

class RequestAndProblemTest {
    @Test
    void requestParametersEnforceCardinalityBoundsTimestampsAndEscaping() {
        RequestParameters parameters = new RequestParameters();

        assertEquals(0, parameters.pagingPage(request()));
        assertEquals(20, parameters.pageSize(request()));
        assertEquals(7, parameters.pagingPage(request(Map.of("page", List.of("7")))));
        assertEquals(100, parameters.pageSize(request(Map.of("size", List.of("100")))));
        assertEquals(214_748_364_700L, parameters.offset(Integer.MAX_VALUE, 100));
        assertEquals("100\\%\\_\\\\", parameters.escapeLike("100%_\\"));
        assertEquals(
                UUID.fromString("11111111-2222-3333-4444-555555555555"),
                parameters.parseUuid("11111111-2222-3333-4444-555555555555"));
        assertEquals(
                Instant.parse("2026-07-01T12:00:00Z"),
                parameters.timeParam(
                        request(Map.of("from", List.of("2026-07-01T12:00:00Z"))), "from"));

        SqlWhere where = new SqlWhere();
        parameters.equalFilter(request(Map.of("actor", List.of("alice"))), where, "actor", "actor");
        assertEquals("where actor = ?", where.sql());
        assertEquals(List.of("alice"), where.params());

        assertProblem(400, () -> parameters.pagingPage(request(Map.of("page", List.of("-1")))));
        assertProblem(400, () -> parameters.pageSize(request(Map.of("size", List.of("0")))));
        assertProblem(400, () -> parameters.pageSize(request(Map.of("size", List.of("101")))));
        assertProblem(400, () -> parameters.pagingPage(request(Map.of("page", List.of("NaN")))));
        assertProblem(
                400, () -> parameters.singleParam(request(Map.of("q", List.of("a", "b"))), "q"));
        assertProblem(
                400,
                () -> parameters.timeParam(request(Map.of("from", List.of("yesterday"))), "from"));
        assertProblem(404, () -> parameters.parseUuid("not-a-uuid"));
        assertProblem(400, () -> parameters.maxLength("😀😀", 1, "q"));
    }

    @Test
    void authorizationAndIdentityResponsesUseAuthoritativeCallerAndApplicationRoles() {
        StackverseProblem anonymous =
                assertThrows(StackverseProblem.class, () -> authorization(null).requireCaller());
        assertEquals(401, anonymous.status);

        StackverseProblem denied =
                assertThrows(
                        StackverseProblem.class,
                        () -> authorization("reader").requireRole("admin"));
        assertEquals(403, denied.status);

        Authorization admin =
                authorization("alice", "offline_access", "admin", "moderator", "uma_authorization");
        assertEquals("alice", admin.requireRole("admin").username());
        MeResponse me =
                assertInstanceOf(MeResponse.class, new IdentityService(admin).me().getEntity());
        assertEquals("alice", me.username());
        assertEquals("Name alice", me.name());
        assertEquals("alice@example.com", me.email());
        assertEquals(List.of("admin", "moderator"), me.roles());
    }

    @Test
    void allTypedInputsExposeContractValidationKeys() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();

            assertEquals(
                    Set.of("validation.report.reason.invalid"),
                    messages(validator.validate(new ReportInput("invalid", null))));
            assertEquals(
                    Set.of("validation.resolution.invalid"),
                    messages(validator.validate(new ResolutionInput("invalid", null))));
            assertEquals(
                    Set.of("validation.bookmark-status.invalid"),
                    messages(validator.validate(new BookmarkStatusInput("invalid", null))));
            assertEquals(
                    Set.of("validation.block.reason.required"),
                    messages(validator.validate(new UserStatusInput("blocked", null))));
            assertEquals(
                    Set.of("validation.block.reason.too-long"),
                    messages(validator.validate(new UserStatusInput("blocked", "x".repeat(1001)))));
        }
    }

    @Test
    void problemMapperNormalizesFrameworkExceptionsAndRetainsV1DeprecationHeaders() {
        ProblemMapper mapper = new ProblemMapper(new KeyLocalizer());
        var context = request();
        mapper.uriInfo = context.uriInfo();
        mapper.headers = context.headers();
        mapper.request = routeRequest("GET", "api/v1/bookmarks");

        Map<Throwable, Integer> cases = new LinkedHashMap<>();
        cases.put(StackverseProblem.conflict("conflict"), 409);
        cases.put(new NotAuthorizedException("Bearer"), 401);
        cases.put(new ForbiddenException(), 403);
        cases.put(new NotFoundException(), 404);
        cases.put(new NotAllowedException("GET"), 405);
        cases.put(new BadRequestException(), 400);
        cases.put(new WebApplicationException(Response.status(418).build()), 418);
        cases.put(new IllegalStateException("boom"), 500);

        cases.forEach(
                (error, status) -> {
                    Response response = mapper.toResponse(error);
                    Map<?, ?> body = assertInstanceOf(Map.class, response.getEntity());
                    assertEquals(status, response.getStatus());
                    assertEquals(status, body.get("status"));
                    assertEquals("application/problem+json", response.getMediaType().toString());
                    assertEquals("@1782864000", response.getHeaderString("Deprecation"));
                });
    }

    @Test
    void focusedQuarkusMappersReturnContractProblemsAndHealthMapsDependencyFailure()
            throws Exception {
        KeyLocalizer localizer = new KeyLocalizer();
        var context = request();

        QuarkusUnauthorizedMapper unauthorizedMapper = new QuarkusUnauthorizedMapper(localizer);
        unauthorizedMapper.uriInfo = context.uriInfo();
        unauthorizedMapper.headers = context.headers();
        Response unauthorized =
                unauthorizedMapper.toResponse(new io.quarkus.security.UnauthorizedException());
        assertEquals(401, unauthorized.getStatus());

        QuarkusForbiddenMapper forbiddenMapper =
                new QuarkusForbiddenMapper(localizer, identity("reader"));
        forbiddenMapper.uriInfo = context.uriInfo();
        forbiddenMapper.headers = context.headers();
        Response forbidden =
                forbiddenMapper.toResponse(new io.quarkus.security.ForbiddenException());
        assertEquals(403, forbidden.getStatus());

        JsonMappingExceptionMapper jsonMapper = new JsonMappingExceptionMapper(localizer);
        jsonMapper.uriInfo = context.uriInfo();
        jsonMapper.headers = context.headers();
        Response malformed =
                jsonMapper.toResponse(
                        MismatchedInputException.from(null, String.class, "wrong JSON shape"));
        assertEquals(400, malformed.getStatus());

        HealthService failedHealth = new HealthService(failingDataSource());
        assertEquals(200, failedHealth.healthz().getStatus());
        assertEquals(503, failedHealth.readyz().getStatus());
        assertThrows(
                DbException.class,
                () ->
                        new DatabaseOperations(failingDataSource())
                                .withConnection(connection -> null));
    }

    private static Set<String> messages(
            Set<? extends jakarta.validation.ConstraintViolation<?>> violations) {
        return violations.stream()
                .map(jakarta.validation.ConstraintViolation::getMessage)
                .collect(java.util.stream.Collectors.toSet());
    }

    private static void assertProblem(int status, Runnable operation) {
        StackverseProblem problem = assertThrows(StackverseProblem.class, operation::run);
        assertEquals(status, problem.status);
        assertNotNull(problem.title);
    }

    private static DataSource failingDataSource() {
        return (DataSource)
                Proxy.newProxyInstance(
                        DataSource.class.getClassLoader(),
                        new Class<?>[] {DataSource.class},
                        (proxy, method, args) -> {
                            if ("getConnection".equals(method.getName())) {
                                throw new SQLException("database unavailable");
                            }
                            if (method.getReturnType() == boolean.class) {
                                return false;
                            }
                            if (method.getReturnType() == int.class) {
                                return 0;
                            }
                            return null;
                        });
    }

    private static final class KeyLocalizer extends Localizer {
        private KeyLocalizer() {
            super(null);
        }

        @Override
        String resolveLanguage(UriInfo uriInfo, jakarta.ws.rs.core.HttpHeaders headers) {
            return "en";
        }

        @Override
        String localize(String key, String language) {
            return key;
        }

        @Override
        Map<String, String> localizeAll(Set<String> keys, String language) {
            Map<String, String> messages = new LinkedHashMap<>();
            keys.forEach(key -> messages.put(key, key));
            return messages;
        }
    }
}
