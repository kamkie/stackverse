package dev.stackverse.backend.support

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

class SqlRows {
    static Instant instant(ResultSet rs, String column) {
        Timestamp value = rs.getTimestamp(column)
        value == null ? null : value.toInstant()
    }

    static String rfc3339(Instant instant) {
        instant?.toString()
    }

    static UUID uuid(ResultSet rs, String column) {
        Object value = rs.getObject(column)
        value instanceof UUID ? value : UUID.fromString(value.toString())
    }
}
