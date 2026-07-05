package dev.stackverse.backend;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MutableHttpRequest;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

class ControllerValidationTest {
    private final SecuritySupport security = new SecuritySupport();

    @Test
    void bookmarkCreationReportsAllFieldValidationErrorsBeforePersistence() {
        BookmarksController controller = new BookmarksController(null, security);
        BookmarkInput input = new BookmarkInput(
                "ftp://example.test",
                "   ",
                "x".repeat(4001),
                List.of("valid", "bad tag"),
                Models.PRIVATE
        );

        assertThatThrownBy(() -> controller.create(authenticated("demo", List.of()), input))
                .isInstanceOfSatisfying(ProblemException.class, problem -> {
                    assertThat(problem.status.getCode()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
                    assertThat(problem.fields)
                            .extracting(FieldViolation::field, FieldViolation::messageKey)
                            .contains(
                                    tuple("url", "validation.url.invalid"),
                                    tuple("title", "validation.title.required"),
                                    tuple("notes", "validation.notes.too-long"),
                                    tuple("tags", "validation.tag.invalid")
                            );
                });
    }

    @Test
    void messageCreationReportsAllFieldValidationErrorsBeforeConflictCheck() {
        MessagesController controller = new MessagesController(null, null, null, security, null);
        MessageInput input = new MessageInput(
                "Invalid Key",
                "eng",
                "",
                "x".repeat(1001)
        );

        assertThatThrownBy(() -> controller.create(authenticated("admin", List.of("admin")), input))
                .isInstanceOfSatisfying(ProblemException.class, problem -> {
                    assertThat(problem.status.getCode()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
                    assertThat(problem.fields)
                            .extracting(FieldViolation::field, FieldViolation::messageKey)
                            .contains(
                                    tuple("key", "validation.message.key.invalid"),
                                    tuple("language", "validation.message.language.invalid"),
                                    tuple("text", "validation.message.text.required"),
                                    tuple("description", "validation.message.description.too-long")
                            );
                });
    }

    @Test
    void reportUpdateRejectsInvalidReasonAndOversizedCommentBeforePersistence() {
        ModerationController controller = new ModerationController(null, security, null, null);
        String id = UUID.fromString("00000000-0000-0000-0000-000000000456").toString();
        ReportInput input = new ReportInput("phishing", "x".repeat(1001));

        assertThatThrownBy(() -> controller.updateMine(authenticated("demo", List.of()), id, input))
                .isInstanceOfSatisfying(ProblemException.class, problem -> {
                    assertThat(problem.status.getCode()).isEqualTo(HttpStatus.BAD_REQUEST.getCode());
                    assertThat(problem.fields)
                            .extracting(FieldViolation::field, FieldViolation::messageKey)
                            .contains(
                                    tuple("reason", "validation.report.reason.invalid"),
                                    tuple("comment", "validation.report.comment.too-long")
                            );
                });
    }

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

    private HttpRequest<?> authenticated(String username, List<String> roles) {
        return authenticatedMutable(username, roles, "/api/test");
    }

    private MutableHttpRequest<?> authenticatedMutable(String username, List<String> roles, String uri) {
        MutableHttpRequest<?> request = HttpRequest.GET(uri);
        request.setAttribute(AuthFilter.IDENTITY, new Identity(username, username, username + "@example.test", roles));
        return request;
    }
}
