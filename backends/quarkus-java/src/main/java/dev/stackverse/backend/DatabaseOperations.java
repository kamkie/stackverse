package dev.stackverse.backend;

import jakarta.enterprise.context.ApplicationScoped;
import java.sql.Connection;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.jboss.logging.Logger;

@ApplicationScoped
final class DatabaseOperations {
    private static final Logger LOG = Logger.getLogger(DatabaseOperations.class);

    private final DataSource dataSource;

    DatabaseOperations(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    <T> T withConnection(SqlFunction<Connection, T> function) {
        try (Connection connection = dataSource.getConnection()) {
            return function.apply(connection);
        } catch (SQLException error) {
            throw new DbException(error);
        }
    }

    <T> T inTransaction(SqlFunction<Connection, T> function) {
        return withConnection(
                connection -> {
                    boolean previousAutoCommit = connection.getAutoCommit();
                    connection.setAutoCommit(false);
                    Throwable failure = null;
                    try {
                        T result = function.apply(connection);
                        connection.commit();
                        return result;
                    } catch (SQLException | RuntimeException | Error error) {
                        failure = error;
                        try {
                            connection.rollback();
                        } catch (SQLException rollbackError) {
                            error.addSuppressed(rollbackError);
                        }
                        throw error;
                    } finally {
                        try {
                            connection.setAutoCommit(previousAutoCommit);
                        } catch (SQLException restoreError) {
                            if (failure != null) {
                                failure.addSuppressed(restoreError);
                            } else {
                                LOG.warn(
                                        "Failed to restore connection auto-commit after transaction",
                                        restoreError);
                            }
                        }
                    }
                });
    }
}
