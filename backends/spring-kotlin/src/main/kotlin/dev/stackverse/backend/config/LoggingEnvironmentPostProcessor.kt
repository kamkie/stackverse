package dev.stackverse.backend.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

/**
 * Maps the repo-wide logging env vars (docs/LOGGING.md §8) onto Spring Boot's
 * native logging properties before the logging system initializes. Registered in
 * `META-INF/spring.factories` — configuration stays env-only, no logback.xml.
 *
 * - `LOG_FORMAT` (default `json`) turns on Boot's built-in structured console
 *   logging (ECS flavor: UTC timestamps, MDC and key-value pairs included);
 *   `LOG_FORMAT=text` keeps the human-readable pattern for local dev.
 * - In text mode with telemetry enabled, the trace/span ids that the OTel Java
 *   agent puts into the MDC are appended to the console pattern via Boot's
 *   correlation slot, so every line links to its trace.
 *
 * (`LOG_LEVEL` needs no code: `application.yaml` binds it to `logging.level.root`.)
 */
class LoggingEnvironmentPostProcessor : EnvironmentPostProcessor {

    override fun postProcessEnvironment(environment: ConfigurableEnvironment, application: SpringApplication) {
        val properties = mutableMapOf<String, Any>()
        if (!environment.getProperty("LOG_FORMAT", "json").equals("text", ignoreCase = true)) {
            properties["logging.structured.format.console"] = "ecs"
        } else if (environment.getProperty("OTEL_SDK_DISABLED", "true").equals("false", ignoreCase = true)) {
            properties["logging.pattern.correlation"] = "[%X{trace_id:-},%X{span_id:-}] "
        }
        environment.propertySources.addFirst(MapPropertySource("stackverseLogging", properties))
    }
}
