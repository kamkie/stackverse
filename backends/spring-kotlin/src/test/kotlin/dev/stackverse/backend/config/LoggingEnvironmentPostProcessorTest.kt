package dev.stackverse.backend.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.SpringApplication
import org.springframework.core.env.MapPropertySource
import org.springframework.core.env.StandardEnvironment

class LoggingEnvironmentPostProcessorTest {

    private val processor = LoggingEnvironmentPostProcessor()

    @Test
    fun `json logging is configured by default`() {
        val environment = environment()

        processor.postProcessEnvironment(environment, SpringApplication())

        assertThat(environment.getProperty("logging.structured.format.console")).isEqualTo("ecs")
        assertThat(environment.getProperty("logging.pattern.correlation")).isNull()
    }

    @Test
    fun `text logging disables structured console logging`() {
        val environment = environment("LOG_FORMAT" to "text")

        processor.postProcessEnvironment(environment, SpringApplication())

        assertThat(environment.getProperty("logging.structured.format.console")).isNull()
        assertThat(environment.getProperty("logging.pattern.correlation")).isNull()
    }

    @Test
    fun `text logging includes trace correlation when otel is enabled`() {
        val environment = environment("LOG_FORMAT" to "TeXt", "OTEL_SDK_DISABLED" to "FaLsE")

        processor.postProcessEnvironment(environment, SpringApplication())

        assertThat(environment.getProperty("logging.structured.format.console")).isNull()
        assertThat(environment.getProperty("logging.pattern.correlation")).isEqualTo("[%X{trace_id:-},%X{span_id:-}] ")
    }

    private fun environment(vararg properties: Pair<String, String>) =
        StandardEnvironment().apply {
            propertySources.addFirst(MapPropertySource("test", properties.toMap()))
        }
}
