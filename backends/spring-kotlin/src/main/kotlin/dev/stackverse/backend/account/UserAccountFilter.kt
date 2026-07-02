package dev.stackverse.backend.account

import dev.stackverse.backend.message.MessageLocalizer
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.web.filter.OncePerRequestFilter
import tools.jackson.databind.ObjectMapper

/**
 * Runs right after JWT authentication: upserts the caller's account row (SPEC rule 16)
 * and rejects blocked accounts with a localized 403 problem document (rule 17).
 * Registered inside the security filter chain — deliberately not a `@Component`,
 * which would make Boot register it a second time as a plain servlet filter.
 */
class UserAccountFilter(
    private val accountService: UserAccountService,
    private val localizer: MessageLocalizer,
    private val objectMapper: ObjectMapper,
) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
        val authentication = SecurityContextHolder.getContext().authentication
        if (authentication is JwtAuthenticationToken) {
            val account = accountService.recordSeen(authentication.name)
            if (account.status == UserAccountStatus.BLOCKED) {
                response.status = HttpStatus.FORBIDDEN.value()
                response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
                objectMapper.writeValue(
                    response.outputStream,
                    mapOf(
                        "type" to "about:blank",
                        "title" to "Forbidden",
                        "status" to HttpStatus.FORBIDDEN.value(),
                        "detail" to localizer.localize("error.account.blocked", request),
                    ),
                )
                return
            }
        }
        filterChain.doFilter(request, response)
    }
}
