package dev.stackverse.backend.account

import dev.stackverse.backend.config.EventLogger
import dev.stackverse.backend.support.ApiError
import dev.stackverse.backend.support.JsonSupport
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter

class UserAccountFilter extends OncePerRequestFilter {
    private final UserAccountService accountService
    private final EventLogger eventLogger

    UserAccountFilter(UserAccountService accountService, EventLogger eventLogger) {
        this.accountService = accountService
        this.eventLogger = eventLogger
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain) {
        def authentication = SecurityContextHolder.context.authentication
        if (authentication instanceof JwtAuthenticationToken && authentication.authenticated) {
            String username = authentication.token.claims["preferred_username"]
            if (username) {
                Map account = accountService.touch(username)
                if (account.status == "blocked") {
                    eventLogger.warn("blocked_user_rejected", "denied", "Blocked account rejected", [actor: username])
                    JsonSupport.writeProblem(response, ApiError.forbidden("Your account has been blocked."))
                    return
                }
            }
        }
        filterChain.doFilter(request, response)
    }
}
