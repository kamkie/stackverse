package dev.stackverse.gateway.common

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

/**
 * Sanitizes a client-controlled value before it becomes a log field (docs/LOGGING.md §6):
 * newlines are encoded, other control characters stripped, length capped — mirroring the
 * reference implementation in the Vite client-log forwarder.
 */
fun sanitizeForLog(value: String?, maxLength: Int = 200): String? {
    if (value == null) {
        return null
    }
    val normalized = value.replace("\r\n", "\n") // one newline, one escape
    val builder = StringBuilder(minOf(normalized.length, maxLength))
    for (ch in normalized) {
        if (builder.length >= maxLength) {
            builder.append('…')
            break
        }
        when {
            ch == '\n' || ch == '\r' -> builder.append("\\n")
            !ch.isISOControl() -> builder.append(ch)
        }
    }
    return builder.toString()
}
