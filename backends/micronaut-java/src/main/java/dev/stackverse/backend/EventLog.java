package dev.stackverse.backend;

import org.slf4j.Logger;
import org.slf4j.spi.LoggingEventBuilder;

import java.util.Map;

final class EventLog {
    private EventLog() {
    }

    static void info(Logger logger, String event, String outcome, String message) {
        with(logger.atInfo(), event, outcome).log(message);
    }

    static void info(Logger logger, String event, String outcome, String message, Map<String, ?> fields) {
        LoggingEventBuilder builder = with(logger.atInfo(), event, outcome);
        fields.forEach(builder::addKeyValue);
        builder.log(message);
    }

    static void warn(Logger logger, String event, String outcome, String message, Map<String, ?> fields) {
        LoggingEventBuilder builder = with(logger.atWarn(), event, outcome);
        fields.forEach(builder::addKeyValue);
        builder.log(message);
    }

    private static LoggingEventBuilder with(LoggingEventBuilder builder, String event, String outcome) {
        return builder.addKeyValue("event", event).addKeyValue("outcome", outcome);
    }
}
