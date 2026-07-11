package dev.stackverse.backend.message;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.stackverse.backend.account.UserAccount;
import dev.stackverse.backend.account.UserAccountService;
import dev.stackverse.backend.account.UserAccountStatus;
import dev.stackverse.backend.common.ApiExceptionHandler;
import dev.stackverse.backend.config.SecurityConfig;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(MessageController.class)
@Import({SecurityConfig.class, ApiExceptionHandler.class})
class MessageSecurityWebMvcTest {
    private static final String CREATE_BODY = """
        {"key":"ui.action.save","language":"en","text":"Save"}
        """;

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private MessageRepository repository;

    @MockitoBean
    private MessageService service;

    @MockitoBean
    private LanguageResolver languageResolver;

    @MockitoBean
    private UserAccountService accountService;

    @MockitoBean
    private MessageLocalizer localizer;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @Test
    void anonymousMutationGetsUnauthorizedProblem() throws Exception {
        mockMvc.perform(post("/api/v1/messages")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_BODY))
            .andExpect(status().isUnauthorized())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Unauthorized"))
            .andExpect(jsonPath("$.status").value(401))
            .andExpect(jsonPath("$.detail").value("Authentication is required."));

        verify(service, never()).create(any(), any());
    }

    @Test
    void authenticatedUserWithoutAdminRoleGetsForbiddenProblem() throws Exception {
        when(jwtDecoder.decode("regular-token")).thenReturn(jwt("regular-token", "alice", List.of()));
        when(accountService.recordSeen("alice")).thenReturn(account("alice", UserAccountStatus.ACTIVE));

        mockMvc.perform(post("/api/v1/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer regular-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_BODY))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.title").value("Forbidden"))
            .andExpect(jsonPath("$.status").value(403))
            .andExpect(jsonPath("$.detail").value("You do not have the role required for this operation."));

        verify(accountService).recordSeen("alice");
        verify(service, never()).create(any(), any());
    }

    @Test
    void adminRealmRoleReachesMutationWithJwtUsername() throws Exception {
        Message created = message();
        MessageRequest request = new MessageRequest("ui.action.save", "en", "Save", null);
        when(jwtDecoder.decode("admin-token")).thenReturn(jwt("admin-token", "admin-user", List.of("admin")));
        when(accountService.recordSeen("admin-user"))
            .thenReturn(account("admin-user", UserAccountStatus.ACTIVE));
        when(service.create("admin-user", request)).thenReturn(created);

        mockMvc.perform(post("/api/v1/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer admin-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_BODY))
            .andExpect(status().isCreated())
            .andExpect(header().string(HttpHeaders.LOCATION, "/api/v1/messages/" + created.getId()))
            .andExpect(jsonPath("$.id").value(created.getId().toString()))
            .andExpect(jsonPath("$.key").value("ui.action.save"));

        verify(accountService).recordSeen("admin-user");
        verify(service).create("admin-user", request);
    }

    @Test
    void blockedAdminIsRejectedBeforeController() throws Exception {
        when(jwtDecoder.decode("blocked-token")).thenReturn(jwt("blocked-token", "blocked-admin", List.of("admin")));
        when(accountService.recordSeen("blocked-admin"))
            .thenReturn(account("blocked-admin", UserAccountStatus.BLOCKED));
        when(localizer.localize(org.mockito.ArgumentMatchers.eq("error.account.blocked"), any()))
            .thenReturn("This account is blocked.");

        mockMvc.perform(post("/api/v1/messages")
                .header(HttpHeaders.AUTHORIZATION, "Bearer blocked-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content(CREATE_BODY))
            .andExpect(status().isForbidden())
            .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
            .andExpect(jsonPath("$.detail").value("This account is blocked."));

        verify(service, never()).create(any(), any());
    }

    private static Jwt jwt(String tokenValue, String username, List<String> roles) {
        return Jwt.withTokenValue(tokenValue)
            .header("alg", "none")
            .claim("sub", username)
            .claim("preferred_username", username)
            .claim("realm_access", Map.of("roles", roles))
            .issuedAt(Instant.EPOCH)
            .expiresAt(Instant.parse("2099-01-01T00:00:00Z"))
            .build();
    }

    private static UserAccount account(String username, UserAccountStatus status) {
        return new UserAccount(username, Instant.EPOCH, Instant.EPOCH, status, null);
    }

    private static Message message() {
        return new Message("ui.action.save", "en", "Save", null, Instant.EPOCH, Instant.EPOCH);
    }
}
