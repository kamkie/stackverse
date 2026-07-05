package dev.stackverse.backend

import ch.qos.logback.classic.LoggerContext
import ch.qos.logback.classic.encoder.PatternLayoutEncoder
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.ConsoleAppender
import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.SerializationFeature
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule
import com.fasterxml.jackson.module.kotlin.KotlinModule
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import net.logstash.logback.encoder.LogstashEncoder
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import java.net.URI
import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.Locale
import kotlin.math.ceil
import ch.qos.logback.classic.Level as LogbackLevel

fun isHttpUrl(url: String): Boolean {
    val uri = runCatching { URI(url) }.getOrNull() ?: return false
    return uri.isAbsolute && uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank()
}

fun nowUtc(): Instant = Instant.now().truncatedTo(ChronoUnit.MICROS)

fun pages(total: Long, size: Int): Int = if (total == 0L) 0 else ceil(total.toDouble() / size).toInt()

fun hikari(config: Config): HikariDataSource =
    HikariDataSource(
        HikariConfig().apply {
            jdbcUrl = config.jdbcUrl
            username = config.dbUser
            password = config.dbPassword
            maximumPoolSize = 10
            minimumIdle = 1
            poolName = "stackverse-ktor"
        },
    )

fun jsonMapper(): ObjectMapper = ObjectMapper()
    .registerModule(KotlinModule.Builder().build())
    .registerModule(JavaTimeModule())
    .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
    .setDefaultPropertyInclusion(JsonInclude.Include.NON_NULL)

fun configureLogging(config: Config) {
    val context = LoggerFactory.getILoggerFactory() as LoggerContext
    context.reset()
    val root = context.getLogger(Logger.ROOT_LOGGER_NAME)
    root.level = when (config.logLevel.lowercase(Locale.ROOT)) {
        "error" -> LogbackLevel.ERROR
        "warn" -> LogbackLevel.WARN
        "debug" -> LogbackLevel.DEBUG
        else -> LogbackLevel.INFO
    }
    val appender = ConsoleAppender<ILoggingEvent>().apply {
        this.context = context
        encoder = if (config.logFormat.lowercase(Locale.ROOT) == "text") {
            PatternLayoutEncoder().apply {
                this.context = context
                pattern = "%d{yyyy-MM-dd'T'HH:mm:ss.SSSX,UTC} %-5level [%logger] %msg%n%ex"
                start()
            }
        } else {
            LogstashEncoder().apply {
                fieldNames.timestamp = "timestamp"
                start()
            }
        }
        start()
    }
    root.addAppender(appender)
}

fun Logger.logEvent(level: Level, event: String, outcome: String, message: String, vararg fields: Pair<String, Any?>) {
    var builder = atLevel(level)
        .addKeyValue("event", event)
        .addKeyValue("outcome", outcome)
    fields.forEach { (key, value) ->
        if (value != null) builder = builder.addKeyValue(key, value)
    }
    builder.log(message)
}
