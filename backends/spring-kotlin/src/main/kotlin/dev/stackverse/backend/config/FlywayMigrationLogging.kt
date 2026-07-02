package dev.stackverse.backend.config

import dev.stackverse.backend.common.logEvent
import org.flywaydb.core.api.callback.Callback
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.stereotype.Component

/**
 * `db_migration_applied` per migration (docs/LOGGING.md §5). Spring Boot picks up
 * [Callback] beans and registers them with the auto-configured Flyway instance.
 */
@Component
class FlywayMigrationLogging : Callback {

    private val log = LoggerFactory.getLogger(javaClass)

    // Flyway probes `supports` with a null context, so the parameter stays nullable
    override fun supports(event: Event, context: Context?): Boolean = event == Event.AFTER_EACH_MIGRATE

    override fun canHandleInTransaction(event: Event, context: Context?): Boolean = true

    override fun handle(event: Event, context: Context) {
        val info = context.migrationInfo ?: return
        log.logEvent(
            Level.INFO, "db_migration_applied", "success",
            "Applied database migration ${info.version} — ${info.description}",
            "version" to info.version?.version,
            "description" to info.description,
        )
    }

    override fun getCallbackName(): String = javaClass.simpleName
}
