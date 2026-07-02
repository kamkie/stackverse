package dev.stackverse.backend.config

import com.zaxxer.hikari.HikariDataSource
import dev.stackverse.backend.common.logEvent
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import javax.sql.DataSource

/**
 * Lifecycle contract events (docs/LOGGING.md §5): `application_start` once the
 * service is accepting traffic — with the effective configuration, secrets
 * excluded — and `application_stop` on orderly shutdown, so restarts stay
 * distinguishable from crashes (a killed process never emits it).
 */
@Component
class LifecycleLogging(private val environment: Environment, private val dataSource: DataSource) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun onReady(event: ApplicationReadyEvent) {
        log.logEvent(
            Level.INFO, "application_start", "success", "Stackverse backend is up and accepting requests",
            "port" to environment.getProperty("server.port"),
            // the pool's actual JDBC URL (the static property would lie under test-managed
            // connections), with the query part dropped — a deployment overriding the URL
            // can smuggle credentials in as ?user=…&password=… (§6)
            "db_url" to (dataSource as? HikariDataSource)?.jdbcUrl?.substringBefore('?'),
            "oidc_issuer_uri" to environment.getProperty("stackverse.oidc.issuer-uri"),
            "oidc_jwks_uri" to environment.getProperty("stackverse.oidc.jwks-uri")?.ifBlank { null },
            "seed_messages_dir" to environment.getProperty("stackverse.seed.messages-dir"),
            "log_level" to environment.getProperty("logging.level.root"),
            "log_format" to environment.getProperty("LOG_FORMAT", "json"),
            "otel_sdk_disabled" to environment.getProperty("OTEL_SDK_DISABLED", "true"),
        )
    }

    @EventListener
    fun onClosed(event: ContextClosedEvent) {
        log.logEvent(Level.INFO, "application_stop", "success", "Stackverse backend shutting down")
    }
}
