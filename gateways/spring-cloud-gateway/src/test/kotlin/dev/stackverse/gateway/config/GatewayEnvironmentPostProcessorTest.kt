package dev.stackverse.gateway.config

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.mock.env.MockEnvironment

class GatewayEnvironmentPostProcessorTest {

    private val processor = GatewayEnvironmentPostProcessor()
    private val application = SpringApplication(GatewayEnvironmentPostProcessorTest::class.java)

    @Test
    fun `json console logging is the default`() {
        val environment = MockEnvironment()

        processor.postProcessEnvironment(environment, application)

        assertEquals("ecs", environment.getProperty("logging.structured.format.console"))
        assertNull(environment.getProperty("logging.pattern.correlation"))
    }

    @Test
    fun `text logging with telemetry keeps trace correlation and maps spa root`() {
        val environment = MockEnvironment()
            .withProperty("LOG_FORMAT", "TEXT")
            .withProperty("OTEL_SDK_DISABLED", "FALSE")
            .withProperty("SPA_ROOT", "/srv/stackverse/frontend///")

        processor.postProcessEnvironment(environment, application)

        assertNull(environment.getProperty("logging.structured.format.console"))
        assertEquals(
            "[%X{trace_id:-},%X{span_id:-}] ",
            environment.getProperty("logging.pattern.correlation"),
        )
        assertEquals(
            "file:/srv/stackverse/frontend/",
            environment.getProperty("spring.web.resources.static-locations"),
        )
    }

    @Test
    fun `text logging without telemetry and a blank spa root adds no overrides`() {
        val environment = MockEnvironment()
            .withProperty("LOG_FORMAT", "text")
            .withProperty("OTEL_SDK_DISABLED", "true")
            .withProperty("SPA_ROOT", "   ")

        processor.postProcessEnvironment(environment, application)

        assertNull(environment.getProperty("logging.structured.format.console"))
        assertNull(environment.getProperty("logging.pattern.correlation"))
        assertNull(environment.getProperty("spring.web.resources.static-locations"))
    }
}
