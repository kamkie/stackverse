package dev.stackverse.openliberty;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import org.junit.jupiter.api.Test;

class HealthResourceTest {
    @Test
    void readinessReturns503AndLogsDependencyFailureWhenConnectionFails() throws Exception {
        HealthResource resource = new HealthResource();
        resource.runtime = new UnavailableRepository();
        resource.log = logger();
        PrintStream original = System.out;
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try {
            System.setOut(new PrintStream(output, true, StandardCharsets.UTF_8));
            assertFalse(resource.databaseReady());
        } finally {
            System.setOut(original);
        }

        String line = output.toString(StandardCharsets.UTF_8);
        assertTrue(line.contains("\"event\":\"dependency_call_failed\""));
        assertTrue(line.contains("\"dependency\":\"postgres\""));
        assertTrue(line.contains("\"duration_ms\":"));
        assertTrue(line.contains("\"error_code\":\"sqlstate_08001\""));
    }

    private static EventLogger logger() {
        EventLogger logger = new EventLogger();
        logger.config = new AppConfig();
        return logger;
    }

    private static final class UnavailableRepository extends JdbcRepository {
        @Override
        Connection connection() throws SQLException {
            throw new SQLException("database unavailable", "08001");
        }
    }
}
