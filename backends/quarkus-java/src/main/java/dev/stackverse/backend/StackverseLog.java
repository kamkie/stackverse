package dev.stackverse.backend;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.jboss.logging.Logger;
import org.jboss.logging.MDC;

import java.util.LinkedHashMap;
import java.util.Map;

final class StackverseLog {
    private StackverseLog() {
    }

    static void event(Logger logger, Logger.Level level, String event, String outcome,
                      String message, Map<String, ?> fields) {
        Map<String, Object> applied = new LinkedHashMap<>();
        applied.put("event", event);
        applied.put("outcome", outcome);
        if (fields != null) {
            applied.putAll(fields);
        }
        SpanContext span = Span.current().getSpanContext();
        if (span.isValid()) {
            applied.put("trace_id", span.getTraceId());
            applied.put("span_id", span.getSpanId());
        }
        try {
            applied.forEach(MDC::put);
            logger.log(level, message);
        } finally {
            applied.keySet().forEach(MDC::remove);
        }
    }
}
