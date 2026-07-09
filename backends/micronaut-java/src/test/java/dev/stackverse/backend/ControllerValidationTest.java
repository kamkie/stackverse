package dev.stackverse.backend;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ControllerValidationTest {
    private final SecuritySupport security = new SecuritySupport();

    @Test
    void adminAuditLogRejectsMalformedTimestampBeforeQuerying() {
        AdminController controller = new AdminController(null, security, null, null, null);
        MutableHttpRequest<?> request = authenticatedMutable("admin", List.of("admin"), "/api/v1/admin/audit-log");
        request.getParameters().add("from", "not-a-timestamp");

        assertThatThrownBy(() -> controller.auditLog(request))
                .isInstanceOfSatisfying(ProblemException.class, problem -> {
                    assertThat(problem.status.getCode()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
                    assertThat(problem.detail).isEqualTo("from must be an RFC 3339 timestamp");
                });
    }

    private MutableHttpRequest<?> authenticatedMutable(String username, List<String> roles, String uri) {
        MutableHttpRequest<?> request = HttpRequest.GET(uri);
        request.setAttribute(AuthFilter.IDENTITY, new Identity(username, username, username + "@example.test", roles));
        return request;
    }
}
