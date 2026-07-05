package dev.stackverse.backend;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

public class EnvConfigSource implements ConfigSource {
    @Override
    public Map<String, String> getProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("quarkus.log.console.json.enabled", jsonConsoleEnabled());
        String logLevel = System.getenv("LOG_LEVEL");
        if (logLevel != null && !logLevel.isBlank()) {
            properties.put("quarkus.log.level", logLevel.toUpperCase(Locale.ROOT));
        }
        String otelDisabled = otelSdkDisabled();
        properties.put("quarkus.otel.sdk.disabled", otelDisabled);
        if ("false".equals(otelDisabled)) {
            properties.put("quarkus.otel.logs.enabled", "true");
            properties.put("quarkus.otel.metrics.enabled", "true");
        }
        return properties;
    }

    @Override
    public Set<String> getPropertyNames() {
        return getProperties().keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return getProperties().get(propertyName);
    }

    @Override
    public String getName() {
        return "stackverse-env-mapping";
    }

    @Override
    public int getOrdinal() {
        return 275;
    }

    private static String jsonConsoleEnabled() {
        String format = System.getenv().getOrDefault("LOG_FORMAT", "json");
        return Boolean.toString(!"text".equalsIgnoreCase(format));
    }

    private static String otelSdkDisabled() {
        String value = System.getenv().getOrDefault("OTEL_SDK_DISABLED", "true").trim();
        return Boolean.toString(!"false".equalsIgnoreCase(value));
    }
}
