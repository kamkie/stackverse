package dev.stackverse.backend.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.stackverse.backend.message.MessageLocalizer;
import jakarta.servlet.FilterChain;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import tools.jackson.databind.ObjectMapper;

class UserAccountFilterTest {
    private final UserAccountService accountService = mock(UserAccountService.class);
    private final MessageLocalizer localizer = mock(MessageLocalizer.class);
    private final UserAccountFilter filter = new UserAccountFilter(accountService, localizer, new ObjectMapper());

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void anonymousRequestPassesWithoutProvisioningAccount() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/messages");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(request, response);
        verifyNoInteractions(accountService, localizer);
    }

    @Test
    void activeJwtCallerIsProvisionedThenContinues() throws Exception {
        authenticate("alice");
        UserAccount account = account("alice", UserAccountStatus.ACTIVE, null);
        when(accountService.recordSeen("alice")).thenReturn(account);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/bookmarks");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        verify(accountService).recordSeen("alice");
        verify(chain).doFilter(request, response);
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    void blockedJwtCallerGetsLocalizedProblemAndStopsChain() throws Exception {
        authenticate("blocked-user");
        when(accountService.recordSeen("blocked-user"))
            .thenReturn(account("blocked-user", UserAccountStatus.BLOCKED, "policy"));
        when(localizer.localize(eq("error.account.blocked"), any()))
            .thenReturn("This account is blocked.");
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/me");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).isEqualTo(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getContentAsString())
            .contains("\"title\":\"Forbidden\"")
            .contains("\"detail\":\"This account is blocked.\"");
        verify(chain, never()).doFilter(request, response);
        verify(localizer).localize("error.account.blocked", request);
    }

    private static void authenticate(String username) {
        Jwt jwt = Jwt.withTokenValue("token")
            .header("alg", "none")
            .claim("preferred_username", username)
            .issuedAt(Instant.EPOCH)
            .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
            .build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, List.of(), username));
    }

    private static UserAccount account(String username, UserAccountStatus status, String reason) {
        return new UserAccount(username, Instant.EPOCH, Instant.EPOCH, status, reason);
    }
}
