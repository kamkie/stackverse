package dev.stackverse.backend.common;

import org.slf4j.Logger;
import org.slf4j.event.Level;

public final class Logging {
    private Logging() {
    }

    /**
     * Emits a stable contract event using SLF4J key-value pairs, which Spring Boot
     * structured logging and the OTel Java agent export as structured fields.
     */
    public static void logEvent(Logger log, Level level, String event, String outcome, String message, Object... fields) {
        var builder = log.atLevel(level)
            .addKeyValue("event", event)
            .addKeyValue("outcome", outcome);
        for (int i = 0; i + 1 < fields.length; i += 2) {
            if (fields[i + 1] != null) {
                builder = builder.addKeyValue(String.valueOf(fields[i]), fields[i + 1]);
            }
        }
        builder.log(message);
    }
}
