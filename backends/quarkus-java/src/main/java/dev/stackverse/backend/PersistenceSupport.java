package dev.stackverse.backend;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

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
