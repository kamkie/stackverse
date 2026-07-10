package dev.stackverse.backend;

import java.sql.Array;
import java.sql.Connection;
import java.sql.Date;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.postgresql.util.PSQLException;

@FunctionalInterface
interface SqlFunction<T, R> {
    R apply(T value) throws SQLException;
}

@FunctionalInterface
interface RowMapper<T> {
    T map(ResultSet rs) throws SQLException;
}

final class DbException extends RuntimeException {
    DbException(Throwable cause) {
        super(cause);
    }
}

final class SqlWhere {
    private final List<String> conditions = new ArrayList<>();
    private final List<Object> params = new ArrayList<>();

    void and(String condition, Object... values) {
        conditions.add(condition);
        params.addAll(Arrays.asList(values));
    }

    String sql() {
        if (conditions.isEmpty()) {
            return "where true";
        }
        return "where " + String.join(" and ", conditions);
    }

    List<Object> params() {
        return params;
    }
}

final class PersistenceSupport {
    private PersistenceSupport() {}

    static Instant now() {
        return Instant.now().truncatedTo(ChronoUnit.MICROS);
    }

    static Instant instant(ResultSet rs, String column) throws SQLException {
        return rs.getObject(column, Timestamp.class).toInstant();
    }

    static Instant nullableInstant(ResultSet rs, String column) throws SQLException {
        Timestamp timestamp = rs.getObject(column, Timestamp.class);
        return timestamp == null ? null : timestamp.toInstant();
    }

    static List<String> textArray(Array array) throws SQLException {
        if (array == null) {
            return List.of();
        }
        return List.of((String[]) array.getArray());
    }

    static <T> Optional<T> queryOne(
            Connection connection, String sql, List<?> params, RowMapper<T> mapper) {
        List<T> rows = query(connection, sql, params, mapper);
        return rows.isEmpty() ? Optional.empty() : Optional.of(rows.get(0));
    }

    static <T> List<T> query(
            Connection connection, String sql, List<?> params, RowMapper<T> mapper) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(connection, statement, params);
            try (ResultSet rs = statement.executeQuery()) {
                List<T> rows = new ArrayList<>();
                while (rs.next()) {
                    rows.add(mapper.map(rs));
                }
                return rows;
            }
        } catch (SQLException error) {
            throw new DbException(error);
        }
    }

    static void execute(Connection connection, String sql, List<?> params) {
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            bind(connection, statement, params);
            statement.executeUpdate();
        } catch (SQLException error) {
            throw new DbException(error);
        }
    }

    static long scalarLong(Connection connection, String sql, List<?> params) {
        return queryOne(connection, sql, params, rs -> rs.getLong(1)).orElse(0L);
    }

    static boolean isUniqueViolation(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof DbException dbException && dbException.getCause() != null) {
                current = dbException.getCause();
                continue;
            }
            if (current instanceof PSQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            if (current instanceof SQLException sqlException
                    && "23505".equals(sqlException.getSQLState())) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

    static List<Object> params(Object... values) {
        return new ArrayList<>(Arrays.asList(values));
    }

    static Map<String, Object> detail(Object... keyValues) {
        Map<String, Object> body = new LinkedHashMap<>();
        for (int i = 0; i + 1 < keyValues.length; i += 2) {
            body.put(String.valueOf(keyValues[i]), keyValues[i + 1]);
        }
        return body;
    }

    private static void bind(Connection connection, PreparedStatement statement, List<?> params)
            throws SQLException {
        for (int i = 0; i < params.size(); i++) {
            Object value = params.get(i);
            int index = i + 1;
            if (value instanceof List<?> list) {
                String[] array = list.stream().map(String::valueOf).toArray(String[]::new);
                statement.setArray(index, connection.createArrayOf("text", array));
            } else if (value instanceof Instant instant) {
                statement.setTimestamp(index, Timestamp.from(instant));
            } else if (value instanceof LocalDate date) {
                statement.setDate(index, Date.valueOf(date));
            } else {
                statement.setObject(index, value);
            }
        }
    }
}
