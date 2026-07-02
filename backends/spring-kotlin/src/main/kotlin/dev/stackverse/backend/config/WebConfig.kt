package dev.stackverse.backend.config

import dev.stackverse.backend.account.UserAccountStatus
import dev.stackverse.backend.bookmark.Visibility
import dev.stackverse.backend.moderation.ReportStatus
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.boot.web.servlet.FilterRegistrationBean
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.core.Ordered
import org.springframework.format.FormatterRegistry
import org.springframework.web.filter.OncePerRequestFilter
import org.springframework.web.filter.ShallowEtagHeaderFilter
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * The v1 bookmarks listing is a permanent deprecation exhibit (see docs/SPEC.md):
 * deprecated 2026-07-01, nominal sunset 2027-07-01, succeeded by /api/v2/bookmarks.
 */
private const val V1_BOOKMARKS_DEPRECATION = "@1782864000"
private const val V1_BOOKMARKS_SUNSET = "Thu, 01 Jul 2027 00:00:00 GMT"
private const val V1_BOOKMARKS_SUCCESSOR = "</api/v2/bookmarks>; rel=\"successor-version\""

@Configuration
class WebConfig : WebMvcConfigurer {

    /**
     * Enum query parameters use the lowercase wire values from the OpenAPI contract
     * (`visibility=public`, `status=open`), not the Kotlin constant names.
     */
    override fun addFormatters(registry: FormatterRegistry) {
        registry.addConverter(String::class.java, Visibility::class.java) { value ->
            Visibility.entries.find { it.wire == value } ?: throw IllegalArgumentException("unknown visibility: $value")
        }
        registry.addConverter(String::class.java, ReportStatus::class.java) { value ->
            ReportStatus.entries.find { it.wire == value } ?: throw IllegalArgumentException("unknown status: $value")
        }
        registry.addConverter(String::class.java, UserAccountStatus::class.java) { value ->
            UserAccountStatus.entries.find { it.wire == value } ?: throw IllegalArgumentException("unknown status: $value")
        }
    }

    /**
     * ETag / `If-None-Match` / `304` for message reads and stats (SPEC rules 10 + 19).
     * Hashing the response body is what keeps this stateless: any write changes the
     * body, hence the ETag — with no version counter to coordinate between instances.
     */
    @Bean
    fun etagFilter(): FilterRegistrationBean<ShallowEtagHeaderFilter> =
        FilterRegistrationBean(ShallowEtagHeaderFilter()).apply {
            urlPatterns = listOf("/api/v1/messages", "/api/v1/messages/*", "/api/v1/admin/stats")
            order = 10 // inside the response, after the security filter chain has authorized
        }

    /** RFC 9745 / 8594 / 8288 deprecation signaling on every `GET /api/v1/bookmarks` response. */
    @Bean
    fun deprecationHeadersFilter(): FilterRegistrationBean<OncePerRequestFilter> {
        val filter = object : OncePerRequestFilter() {
            override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, filterChain: FilterChain) {
                if (request.method == "GET") {
                    response.setHeader("Deprecation", V1_BOOKMARKS_DEPRECATION)
                    response.setHeader("Sunset", V1_BOOKMARKS_SUNSET)
                    response.setHeader("Link", V1_BOOKMARKS_SUCCESSOR)
                }
                filterChain.doFilter(request, response)
            }
        }
        return FilterRegistrationBean<OncePerRequestFilter>(filter).apply {
            urlPatterns = listOf("/api/v1/bookmarks")
            order = Ordered.HIGHEST_PRECEDENCE
        }
    }
}
