package dev.stackverse.backend.common

import org.slf4j.Logger
import org.slf4j.event.Level

/**
 * Emits a stable contract event (docs/LOGGING.md §5): `event`, `outcome`, and the
 * extra fields travel as SLF4J key-value pairs — structured members of the JSON
 * console output and of OTLP-exported records — while the message stays prose.
 * Null-valued fields are dropped so "when applicable" fields can be passed as-is.
 */
fun Logger.logEvent(level: Level, event: String, outcome: String, message: String, vararg fields: Pair<String, Any?>) {
    var builder = atLevel(level)
        .addKeyValue("event", event)
        .addKeyValue("outcome", outcome)
    for ((key, value) in fields) {
        if (value != null) {
            builder = builder.addKeyValue(key, value)
        }
    }
    builder.log(message)
}
