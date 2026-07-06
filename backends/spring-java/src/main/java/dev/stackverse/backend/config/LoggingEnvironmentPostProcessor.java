package dev.stackverse.backend.config;

import java.util.LinkedHashMap;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

public class LoggingEnvironmentPostProcessor implements EnvironmentPostProcessor {
    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        var properties = new LinkedHashMap<String, Object>();
        if (!"text".equalsIgnoreCase(environment.getProperty("LOG_FORMAT", "json"))) {
            properties.put("logging.structured.format.console", "ecs");
        } else if ("false".equalsIgnoreCase(environment.getProperty("OTEL_SDK_DISABLED", "true"))) {
            properties.put("logging.pattern.correlation", "[%X{trace_id:-},%X{span_id:-}] ");
        }
        environment.getPropertySources().addFirst(new MapPropertySource("stackverseLogging", properties));
    }
}
