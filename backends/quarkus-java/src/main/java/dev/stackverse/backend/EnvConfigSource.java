package dev.stackverse.backend;

import org.eclipse.microprofile.config.spi.ConfigSource;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

public class EnvConfigSource implements ConfigSource {
    private final Map<String, String> properties;

    public EnvConfigSource() {
        this.properties = buildProperties();
    }

    @Override
    public Map<String, String> getProperties() {
        return properties;
    }

    @Override
    public Set<String> getPropertyNames() {
        return properties.keySet();
    }

    @Override
    public String getValue(String propertyName) {
        return properties.get(propertyName);
    }

    @Override
    public String getName() {
        return "stackverse-env-mapping";
    }

    @Override
    public int getOrdinal() {
        return 275;
    }

    private static Map<String, String> buildProperties() {
        Map<String, String> properties = new LinkedHashMap<>();
        properties.put("quarkus.log.console.json.enabled", jsonConsoleEnabled());
        String otelDisabled = otelSdkDisabled();
        properties.put("quarkus.otel.sdk.disabled", otelDisabled);
        if ("false".equals(otelDisabled)) {
            properties.put("quarkus.otel.logs.enabled", "true");
            properties.put("quarkus.otel.metrics.enabled", "true");
        }
        return Map.copyOf(properties);
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
