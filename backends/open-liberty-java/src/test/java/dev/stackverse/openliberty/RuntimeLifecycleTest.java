package dev.stackverse.openliberty;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.sql.SQLException;
import java.util.Map;
import org.junit.jupiter.api.Test;

class RuntimeLifecycleTest {
    @Test
    void runtimeInitializationObservesApplicationContextStartup() throws NoSuchMethodException {
        Method observer =
                RuntimeSupport.class.getDeclaredMethod("onApplicationStart", Object.class);

        assertTrue(observer.getParameters()[0].isAnnotationPresent(Observes.class));
        Initialized initialized = observer.getParameters()[0].getAnnotation(Initialized.class);
        assertEquals(ApplicationScoped.class, initialized.value());
    }

    @Test
    void dependencyErrorsUseStableSqlStateCodes() {
        SQLException failure = new SQLException("unavailable", "08001");

        assertTrue(EventLogger.causedBySqlFailure(new RuntimeException(failure)));
        assertEquals("sqlstate_08001", EventLogger.errorCode(new RuntimeException(failure)));
    }

    @Test
    void terminalStartupFailureIsLoggedAtFatalLevel() {
        EventLogger logger = new EventLogger();
        logger.config = new AppConfig();
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            logger.fatal(
                    "application_start",
                    "failure",
                    "Application failed to start",
                    new IllegalStateException("failed"),
                    Map.of("error_code", "illegalstateexception"));
        } finally {
            System.setOut(original);
        }

        String line = output.toString(StandardCharsets.UTF_8);
        assertTrue(line.contains("\"level\":\"FATAL\""));
        assertTrue(line.contains("\"event\":\"application_start\""));
        assertTrue(line.contains("\"stack_trace\":"));
    }
}
