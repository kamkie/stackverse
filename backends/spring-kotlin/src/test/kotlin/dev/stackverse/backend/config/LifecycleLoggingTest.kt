package dev.stackverse.backend.config

import ch.qos.logback.classic.Logger
import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import com.zaxxer.hikari.HikariDataSource
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.`when`
import org.slf4j.LoggerFactory
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.ContextClosedEvent
import org.springframework.core.env.Environment

class LifecycleLoggingTest {

    @Test
    fun `startup log strips JDBC query credentials and omits blank optional fields`() {
        val environment = mock(Environment::class.java)
        `when`(environment.getProperty("server.port")).thenReturn("8080")
        `when`(environment.getProperty("stackverse.oidc.issuer-uri")).thenReturn("https://idp.example/realms/stackverse")
        `when`(environment.getProperty("stackverse.oidc.jwks-uri")).thenReturn("")
        `when`(environment.getProperty("stackverse.seed.messages-dir")).thenReturn("/app/seed/messages")
        `when`(environment.getProperty("logging.level.root")).thenReturn("info")
        `when`(environment.getProperty("LOG_FORMAT", "json")).thenReturn("json")
        `when`(environment.getProperty("OTEL_SDK_DISABLED", "true")).thenReturn("true")
        val dataSource = HikariDataSource().apply {
            jdbcUrl = "jdbc:postgresql://db.example/stackverse?user=alice&password=secret"
        }
        val appender = listAppender()

        try {
            LifecycleLogging(environment, dataSource).onReady(mock(ApplicationReadyEvent::class.java))

            val event = appender.list.single { fieldMap(it)["event"] == "application_start" }
            val fields = fieldMap(event)
            assertThat(fields)
                .containsEntry("outcome", "success")
                .containsEntry("db_url", "jdbc:postgresql://db.example/stackverse")
                .doesNotContainKey("oidc_jwks_uri")
            assertThat(fields.values.joinToString(" ")).doesNotContain("password", "secret", "user=alice")
        } finally {
            detach(appender)
            dataSource.close()
        }
    }

    @Test
    fun `orderly shutdown emits the stable application stop event`() {
        val appender = listAppender()

        try {
            LifecycleLogging(mock(Environment::class.java), mock(HikariDataSource::class.java))
                .onClosed(mock(ContextClosedEvent::class.java))

            val event = appender.list.single { fieldMap(it)["event"] == "application_stop" }
            assertThat(fieldMap(event)).containsEntry("outcome", "success")
            assertThat(event.formattedMessage).isEqualTo("Stackverse backend shutting down")
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

    private fun logger() = LoggerFactory.getLogger(LifecycleLogging::class.java) as Logger

    private fun fieldMap(event: ILoggingEvent): Map<String, Any?> =
        event.keyValuePairs.orEmpty().associate { it.key to it.value }
}
