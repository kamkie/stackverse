package dev.stackverse.backend.common;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.stackverse.backend.message.MessageLocalizer;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;

class ApiExceptionHandlerTest {
    private final MessageLocalizer localizer = mock(MessageLocalizer.class);
    private final ApiExceptionHandler handler = new ApiExceptionHandler(localizer);
    private final HttpServletRequest request = mock(HttpServletRequest.class);

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void apiProblemUsesDirectDetailWhenNoLocalizationKeyExists() {
        var problem = handler.handleApiProblem(new ConflictProblem("Already exists."), request);

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getTitle()).isEqualTo("Conflict");
        assertThat(problem.getDetail()).isEqualTo("Already exists.");
        verifyNoInteractions(localizer);
    }

    @Test
    void apiProblemLocalizesKeyedDetail() {
        when(localizer.localize("error.bookmark.hidden-publish", request))
            .thenReturn("A hidden bookmark cannot be public.");

        var problem = handler.handleApiProblem(
            new ConflictProblem("fallback", "error.bookmark.hidden-publish"),
            request
        );

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getDetail()).isEqualTo("A hidden bookmark cannot be public.");
        verify(localizer).localize("error.bookmark.hidden-publish", request);
    }

    @Test
    @SuppressWarnings("unchecked")
    void validationProblemMapsEveryFieldToLocalizedRfc9457Errors() {
        when(localizer.localize("validation.url.invalid", request)).thenReturn("Enter a valid URL.");
        when(localizer.localize("validation.title.required", request)).thenReturn("Enter a title.");
        ValidationProblem exception = new ValidationProblem(List.of(
            new FieldViolation("url", "validation.url.invalid"),
            new FieldViolation("title", "validation.title.required")
        ));

        var problem = handler.handleValidation(exception, request);

        assertThat(problem.getStatus()).isEqualTo(400);
        assertThat(problem.getTitle()).isEqualTo("Bad Request");
        assertThat(problem.getDetail()).isEqualTo("Request validation failed.");
        assertThat((List<Map<String, String>>) problem.getProperties().get("errors"))
            .containsExactly(
                Map.of(
                    "field", "url",
                    "messageKey", "validation.url.invalid",
                    "message", "Enter a valid URL."
                ),
                Map.of(
                    "field", "title",
                    "messageKey", "validation.title.required",
                    "message", "Enter a title."
                )
            );
    }

    @Test
    void accessDeniedMapsMethodSecurityFailureToForbiddenProblem() {
        SecurityContextHolder.getContext().setAuthentication(
            UsernamePasswordAuthenticationToken.authenticated("alice", "n/a", List.of())
        );

        var problem = handler.handleAccessDenied(new AccessDeniedException("denied"));

        assertThat(problem.getStatus()).isEqualTo(403);
        assertThat(problem.getTitle()).isEqualTo("Forbidden");
        assertThat(problem.getDetail()).isEqualTo("You do not have the role required for this operation.");
    }
}
