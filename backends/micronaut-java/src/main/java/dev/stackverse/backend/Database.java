package dev.stackverse.backend;

import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.sql.Array;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Singleton
final class Database {
    private final DataSource dataSource;

    Database(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    <T> List<T> query(String sql, RowMapper<T> mapper, Object... args) {
        try (Connection connection = dataSource.getConnection()) {
            return query(connection, sql, mapper, args);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    <T> T one(String sql, RowMapper<T> mapper, Object... args) {
        List<T> rows = query(sql, mapper, args);
        if (rows.isEmpty()) {
            throw Problems.notFound();
        }
        return rows.getFirst();
    }

    long scalarLong(String sql, Object... args) {
        return one(sql, rs -> rs.getLong(1), args);
    }

    boolean scalarBoolean(String sql, Object... args) {
        return one(sql, rs -> rs.getBoolean(1), args);
    }

    int update(String sql, Object... args) {
        try (Connection connection = dataSource.getConnection()) {
            return update(connection, sql, args);
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    <T> T inTx(TxWork<T> work) {
        try (Connection connection = dataSource.getConnection()) {
            boolean autoCommit = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.run(connection);
                connection.commit();
                return result;
            } catch (RuntimeException | SQLException ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(autoCommit);
            }
        } catch (ProblemException ex) {
            throw ex;
        } catch (SQLException ex) {
            throw new IllegalStateException(ex);
        }
    }

    <T> List<T> query(Connection connection, String sql, RowMapper<T> mapper, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(connection, statement, args);
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        }
    }

    <T> T one(Connection connection, String sql, RowMapper<T> mapper, Object... args) throws SQLException {
        List<T> rows = query(connection, sql, mapper, args);
        if (rows.isEmpty()) {
            throw Problems.notFound();
        }
        return rows.getFirst();
    }

    long scalarLong(Connection connection, String sql, Object... args) throws SQLException {
        return one(connection, sql, rs -> rs.getLong(1), args);
    }

    boolean scalarBoolean(Connection connection, String sql, Object... args) throws SQLException {
        return one(connection, sql, rs -> rs.getBoolean(1), args);
    }

    int update(Connection connection, String sql, Object... args) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(connection, statement, args);
            return statement.executeUpdate();
        }
    }

    private void bind(Connection connection, PreparedStatement statement, Object... args) throws SQLException {
        for (int i = 0; i < args.length; i++) {
            Object value = args[i];
            int index = i + 1;
            if (value instanceof Instant instant) {
                statement.setTimestamp(index, Timestamp.from(instant));
            } else if (value instanceof UUID uuid) {
                statement.setObject(index, uuid);
            } else if (value instanceof List<?> list) {
                Array array = connection.createArrayOf("text", list.toArray());
                statement.setArray(index, array);
            } else {
                statement.setObject(index, value);
            }
        }
    }
}

@FunctionalInterface
interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
}

@FunctionalInterface
interface TxWork<T> {
    T run(Connection connection) throws SQLException;
}
