package dev.stackverse.gateway.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Maps the repo-wide env vars that have no direct property binding onto Spring Boot
 * configuration before the context starts. Registered in `META-INF/spring.factories` —
 * configuration stays env-only, no logback.xml, no profiles.
 *
 * - `LOG_FORMAT` (default `json`, docs/LOGGING.md §8) turns on Boot's built-in
 *   structured console logging (ECS flavor: UTC timestamps, MDC and key-value pairs
 *   included); `LOG_FORMAT=text` keeps the human-readable pattern for local dev.
 * - In text mode with telemetry enabled, the trace/span ids that the OTel Java
 *   agent puts into the MDC are appended to the console pattern via Boot's
 *   correlation slot, so every line links to its trace.
 * - `SPA_ROOT` points the static-resource location at a frontend production build
 *   (compose mounts the frontend image's build there); unset, the bundled
 *   placeholder page in `classpath:/static/` is served instead.
 *
 * (`LOG_LEVEL` needs no code: `application.yaml` binds it to `logging.level.root`.)
 */
class GatewayEnvironmentPostProcessor : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val properties = mutableMapOf<String, Any>()
        if (!environment.getProperty("LOG_FORMAT", "json").equals("text", ignoreCase = true)) {
            properties["logging.structured.format.console"] = "ecs"
        } else if (environment.getProperty("OTEL_SDK_DISABLED", "true").equals("false", ignoreCase = true)) {
            properties["logging.pattern.correlation"] = "[%X{trace_id:-},%X{span_id:-}] "
        }
        environment.getProperty("SPA_ROOT")?.takeIf { it.isNotBlank() }?.let { root ->
            properties["spring.web.resources.static-locations"] = "file:" + root.trimEnd('/') + "/"
        }
        environment.propertySources.addFirst(MapPropertySource("stackverseGateway", properties))
    }
}
