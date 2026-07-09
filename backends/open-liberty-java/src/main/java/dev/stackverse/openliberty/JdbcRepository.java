package dev.stackverse.openliberty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

/**
 * Injected repository facade that keeps JDBC out of JAX-RS lifecycle and configuration code. Domain
 * SQL remains explicit in the variant for cross-stack comparison.
 */
@ApplicationScoped
public class JdbcRepository {
    @Inject RuntimeSupport runtime;

    Connection connection() throws SQLException {
        return runtime.connection();
    }

    PreparedStatement prepare(Connection connection, String sql, Object... params)
            throws SQLException {
        return runtime.prepare(connection, sql, params);
    }

    void bind(PreparedStatement statement, int index, Object value, Connection connection)
            throws SQLException {
        runtime.bind(statement, index, value, connection);
    }

    <T> T transaction(RuntimeSupport.SqlFunction<Connection, T> work) {
        return runtime.transaction(work);
    }
}
