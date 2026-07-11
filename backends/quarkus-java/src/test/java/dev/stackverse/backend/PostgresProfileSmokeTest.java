package dev.stackverse.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.quarkus.test.junit.QuarkusTest;
import io.quarkus.test.junit.TestProfile;
import jakarta.inject.Inject;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;

@QuarkusTest
@TestProfile(PostgresTestProfile.class)
class PostgresProfileSmokeTest {
    @Inject DataSource dataSource;

    @Test
    void startsPostgresAndAppliesTheFlywaySchema() throws SQLException {
        try (var connection = dataSource.getConnection();
                var statement =
                        connection.prepareStatement(
                                "select count(*) from information_schema.tables"
                                        + " where table_schema = 'public' and table_name = 'bookmarks'");
                var result = statement.executeQuery()) {
            result.next();
            assertEquals(1, result.getLong(1));
        }
    }
}
