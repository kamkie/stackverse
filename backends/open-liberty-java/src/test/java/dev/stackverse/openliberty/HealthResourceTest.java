package dev.stackverse.openliberty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class HealthResourceTest {
    @Test
    void readinessLogsOnlyDependencyStateTransitions() throws Exception {
        HealthResource resource = new HealthResource();
        ToggleRuntime runtime = new ToggleRuntime();
        resource.runtime = runtime;
        resource.log = logger();
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertFalse(resource.databaseReady());
            assertFalse(resource.databaseReady());
            runtime.available = true;
            assertTrue(resource.databaseReady());
            assertTrue(resource.databaseReady());
        } finally {
            System.setOut(original);
        }

        String lines = output.toString(StandardCharsets.UTF_8);
        assertTrue(lines.contains("\"level\":\"WARN\""));
        assertTrue(lines.contains("\"event\":\"dependency_call_failed\""));
        assertTrue(lines.contains("\"dependency\":\"postgres\""));
        assertTrue(lines.contains("\"duration_ms\":"));
        assertTrue(lines.contains("\"error_code\":\"sqlstate_08001\""));
        assertTrue(lines.contains("\"level\":\"INFO\""));
        assertTrue(lines.contains("\"event\":\"dependency_recovered\""));
        assertFalse(lines.contains("\"stack_trace\""));
        assertTrue(
                lines.indexOf("dependency_call_failed")
                        == lines.lastIndexOf("dependency_call_failed"));
        assertTrue(
                lines.indexOf("dependency_recovered") == lines.lastIndexOf("dependency_recovered"));
    }

    private static EventLogger logger() {
        EventLogger logger = new EventLogger();
        logger.config = new AppConfig();
        return logger;
    }

    private static final class ToggleRuntime extends RuntimeSupport {
        boolean available;

        @Override
        Connection connection() throws SQLException {
            if (!available) throw new SQLException("database unavailable", "08001");
            PreparedStatement statement =
                    (PreparedStatement)
                            Proxy.newProxyInstance(
                                    getClass().getClassLoader(),
                                    new Class<?>[] {PreparedStatement.class},
                                    (proxy, method, args) -> defaultValue(method.getReturnType()));
            return (Connection)
                    Proxy.newProxyInstance(
                            getClass().getClassLoader(),
                            new Class<?>[] {Connection.class},
                            (proxy, method, args) ->
                                    "prepareStatement".equals(method.getName())
                                            ? statement
                                            : defaultValue(method.getReturnType()));
        }

        private static Object defaultValue(Class<?> type) {
            if (!type.isPrimitive()) return null;
            if (type == boolean.class) return false;
            if (type == byte.class) return (byte) 0;
            if (type == short.class) return (short) 0;
            if (type == int.class) return 0;
            if (type == long.class) return 0L;
            if (type == float.class) return 0F;
            if (type == double.class) return 0D;
            if (type == char.class) return '\0';
            return null;
        }
    }
}
