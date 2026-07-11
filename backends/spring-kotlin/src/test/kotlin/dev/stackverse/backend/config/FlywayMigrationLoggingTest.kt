package dev.stackverse.backend.config

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import org.assertj.core.api.Assertions.assertThat
import org.flywaydb.core.api.MigrationInfo
import org.flywaydb.core.api.MigrationVersion
import org.flywaydb.core.api.callback.Context
import org.flywaydb.core.api.callback.Event
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory

class FlywayMigrationLoggingTest {

    private val callback = FlywayMigrationLogging()

    @Test
    fun `callback supports only applied migrations and runs inside the transaction`() {
        assertThat(callback.supports(Event.AFTER_EACH_MIGRATE, null)).isTrue()
        assertThat(callback.supports(Event.BEFORE_EACH_MIGRATE, null)).isFalse()
        assertThat(callback.canHandleInTransaction(Event.AFTER_EACH_MIGRATE, null)).isTrue()
        assertThat(callback.callbackName).isEqualTo("FlywayMigrationLogging")
    }

    @Test
    fun `migration callback emits structured version and description`() {
        val context = mock(Context::class.java)
        val migration = mock(MigrationInfo::class.java)
        `when`(context.migrationInfo).thenReturn(migration)
        `when`(migration.version).thenReturn(MigrationVersion.fromVersion("1"))
        `when`(migration.description).thenReturn("schema")
        val appender = listAppender()

        try {
            callback.handle(Event.AFTER_EACH_MIGRATE, context)

            val event = appender.list.single()
            val fields = event.keyValuePairs.orEmpty().associate { it.key to it.value }
            assertThat(fields)
                .containsEntry("event", "db_migration_applied")
                .containsEntry("outcome", "success")
                .containsEntry("version", "1")
                .containsEntry("description", "schema")
        } finally {
            detach(appender)
        }
    }

    @Test
    fun `migration callback ignores events without migration information`() {
        val context = mock(Context::class.java)
        `when`(context.migrationInfo).thenReturn(null)
        val appender = listAppender()

        try {
            callback.handle(Event.AFTER_EACH_MIGRATE, context)

            assertThat(appender.list).isEmpty()
        } finally {
            detach(appender)
        }
    }

    private fun listAppender(): ListAppender<ILoggingEvent> {
        val appender = ListAppender<ILoggingEvent>()
        appender.start()
        logger().addAppender(appender)
        return appender
    }

    private fun detach(appender: ListAppender<ILoggingEvent>) {
        logger().detachAppender(appender)
        appender.stop()
    }

    private fun logger() = LoggerFactory.getLogger(FlywayMigrationLogging::class.java) as Logger
}
