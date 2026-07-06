package dev.stackverse.backend.config;

import dev.stackverse.backend.account.UserAccountStatus;
import dev.stackverse.backend.bookmark.Visibility;
import dev.stackverse.backend.moderation.ReportStatus;
import jakarta.servlet.FilterChain;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.format.FormatterRegistry;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.filter.ShallowEtagHeaderFilter;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {
    private static final String V1_BOOKMARKS_DEPRECATION = "@1782864000";
    private static final String V1_BOOKMARKS_SUNSET = "Thu, 01 Jul 2027 00:00:00 GMT";
    private static final String V1_BOOKMARKS_SUCCESSOR = "</api/v2/bookmarks>; rel=\"successor-version\"";

    @Override
    public void addFormatters(FormatterRegistry registry) {
        registry.addConverter(String.class, Visibility.class, value ->
            Visibility.fromWire(value).orElseThrow(() -> new IllegalArgumentException("unknown visibility: " + value))
        );
        registry.addConverter(String.class, ReportStatus.class, value ->
            ReportStatus.fromWire(value).orElseThrow(() -> new IllegalArgumentException("unknown status: " + value))
        );
        registry.addConverter(String.class, UserAccountStatus.class, value ->
            UserAccountStatus.fromWire(value).orElseThrow(() -> new IllegalArgumentException("unknown status: " + value))
        );
    }

    @Bean
    FilterRegistrationBean<ShallowEtagHeaderFilter> etagFilter() {
        FilterRegistrationBean<ShallowEtagHeaderFilter> registration = new FilterRegistrationBean<>(new ShallowEtagHeaderFilter());
        registration.setUrlPatterns(java.util.List.of("/api/v1/messages", "/api/v1/messages/*", "/api/v1/admin/stats"));
        registration.setOrder(10);
        return registration;
    }

    @Bean
    FilterRegistrationBean<OncePerRequestFilter> deprecationHeadersFilter() {
        OncePerRequestFilter filter = new OncePerRequestFilter() {
            @Override
            protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
                throws java.io.IOException, jakarta.servlet.ServletException {
                if ("GET".equals(request.getMethod())) {
                    response.setHeader("Deprecation", V1_BOOKMARKS_DEPRECATION);
                    response.setHeader("Sunset", V1_BOOKMARKS_SUNSET);
                    response.setHeader("Link", V1_BOOKMARKS_SUCCESSOR);
                }
                filterChain.doFilter(request, response);
            }
        };
        FilterRegistrationBean<OncePerRequestFilter> registration = new FilterRegistrationBean<>(filter);
        registration.setUrlPatterns(java.util.List.of("/api/v1/bookmarks"));
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE);
        return registration;
    }
}
