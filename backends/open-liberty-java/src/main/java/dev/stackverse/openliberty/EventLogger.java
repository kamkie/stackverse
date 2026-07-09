package dev.stackverse.openliberty;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stackverse structured-event logger with CDI-managed configuration. */
@ApplicationScoped
public class EventLogger {
    @Inject AppConfig config;

    void event(String level, String event, String outcome, String message, Map<String, ?> fields) {
        if (!enabled(level)) {
            return;
        }
        Map<String, Object> line = new LinkedHashMap<>();
        line.put("timestamp", Instant.now().toString());
        line.put("level", level.toUpperCase());
        line.put("logger", "stackverse.open-liberty-java");
        line.put("message", message);
        line.put("event", event);
        line.put("outcome", outcome);
        SpanContext spanContext = Span.current().getSpanContext();
        if (spanContext.isValid()) {
            line.put("trace_id", spanContext.getTraceId());
            line.put("span_id", spanContext.getSpanId());
        }
        fields.forEach(line::put);
        if ("text".equals(config.logFormat())) {
            System.out.println(line);
        } else {
            System.out.println(JsonSupport.jsonString(line));
        }
    }

    void error(
            String event,
            String outcome,
            String message,
            Throwable throwable,
            Map<String, ?> fields) {
        Map<String, Object> withError = new LinkedHashMap<>(fields);
        withError.put("stack_trace", stackTrace(throwable));
        event("error", event, outcome, message, withError);
    }

    private boolean enabled(String level) {
        return severity(level) <= severity(config.logLevel());
    }

    private static int severity(String level) {
        return switch (level.toLowerCase()) {
            case "error", "fatal" -> 0;
            case "warn" -> 1;
            case "debug" -> 3;
            default -> 2;
        };
    }

    private static String stackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }
}
