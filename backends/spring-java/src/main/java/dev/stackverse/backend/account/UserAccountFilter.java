package dev.stackverse.backend.account;

import dev.stackverse.backend.common.Logging;
import dev.stackverse.backend.message.MessageLocalizer;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Map;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

/**
 * Runs after JWT auth: upserts the caller account and rejects blocked accounts.
 */
public class UserAccountFilter extends OncePerRequestFilter {
    private final UserAccountService accountService;
    private final MessageLocalizer localizer;
    private final ObjectMapper objectMapper;
    private final org.slf4j.Logger log = LoggerFactory.getLogger(getClass());

    public UserAccountFilter(UserAccountService accountService, MessageLocalizer localizer, ObjectMapper objectMapper) {
        this.accountService = accountService;
        this.localizer = localizer;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
        throws ServletException, IOException {
        var authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication instanceof JwtAuthenticationToken token) {
            UserAccount account = accountService.recordSeen(token.getName());
            if (account.getStatus() == UserAccountStatus.BLOCKED) {
                Logging.logEvent(
                    log,
                    Level.WARN,
                    "blocked_user_rejected",
                    "denied",
                    "Refused a request from a blocked account",
                    "actor", token.getName()
                );
                response.setStatus(HttpStatus.FORBIDDEN.value());
                response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
                objectMapper.writeValue(
                    response.getOutputStream(),
                    Map.of(
                        "type", "about:blank",
                        "title", "Forbidden",
                        "status", HttpStatus.FORBIDDEN.value(),
                        "detail", localizer.localize("error.account.blocked", request)
                    )
                );
                return;
            }
        }
        filterChain.doFilter(request, response);
    }
}
