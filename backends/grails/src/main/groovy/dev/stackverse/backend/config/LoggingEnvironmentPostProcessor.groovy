package dev.stackverse.backend.config

import org.springframework.boot.SpringApplication
import org.springframework.boot.env.EnvironmentPostProcessor
import org.springframework.core.Ordered
import org.springframework.core.env.ConfigurableEnvironment
import org.springframework.core.env.MapPropertySource

class LoggingEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {
    @Override
    void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String format = environment.getProperty("LOG_FORMAT", "json").toLowerCase(Locale.ROOT)
        Map<String, Object> properties = [
            "logging.level.root": environment.getProperty("LOG_LEVEL", "info")
        ]
        if (format != "text") {
            properties["logging.structured.format.console"] = "ecs"
        }
        environment.propertySources.addFirst(new MapPropertySource("stackverseLogging", properties))
    }

    @Override
    int getOrder() {
        Ordered.HIGHEST_PRECEDENCE
    }
}
