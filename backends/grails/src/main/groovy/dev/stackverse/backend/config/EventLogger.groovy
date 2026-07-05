package dev.stackverse.backend.config

import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Component

@Component
class EventLogger {
    private static final Logger log = LoggerFactory.getLogger(EventLogger)

    void info(String event, String outcome, String message, Map<String, Object> values = [:]) {
        def builder = log.atInfo()
            .addKeyValue("event", event)
            .addKeyValue("outcome", outcome)
        values.each { key, value -> builder.addKeyValue(key, value) }
        builder.log(message)
    }

    void warn(String event, String outcome, String message, Map<String, Object> values = [:]) {
        def builder = log.atWarn()
            .addKeyValue("event", event)
            .addKeyValue("outcome", outcome)
        values.each { key, value -> builder.addKeyValue(key, value) }
        builder.log(message)
    }

    @EventListener
    void ready(ApplicationReadyEvent ignored) {
        info("application_start", "success", "Grails backend started", [
            port: System.getenv("PORT") ?: "8080",
            log_format: System.getenv("LOG_FORMAT") ?: "json"
        ])
    }

    @EventListener
    void stopping(ContextClosedEvent ignored) {
        info("application_stop", "success", "Grails backend stopped")
    }
}
