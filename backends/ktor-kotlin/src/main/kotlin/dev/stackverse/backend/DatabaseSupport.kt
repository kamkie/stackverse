package dev.stackverse.backend

import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.Statement
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneOffset
import java.util.Locale
import java.util.UUID

data class Clause(val sql: String, val args: List<Any?> = emptyList()) {
    constructor(sql: String, vararg args: Any?) : this(sql, args.toList())

    fun and(part: String, vararg values: Any?): Clause =
        Clause("($sql) and ($part)", args + values.toList())
}

fun Connection.queryLong(sql: String, args: List<Any?> = emptyList()): Long =
    query(sql, args) { it.getLong(1) }.first()

fun <T> Connection.query(sql: String, args: List<Any?> = emptyList(), mapper: (ResultSet) -> T): List<T> =
    prepareStatement(sql).use { statement ->
        statement.bind(args)
        statement.executeQuery().use { rs ->
            buildList {
                while (rs.next()) add(mapper(rs))
            }
        }
    }

fun Connection.execute(sql: String, vararg args: Any?) {
    prepareStatement(sql).use { statement ->
        statement.bind(args.toList())
        statement.executeUpdate()
    }
}

fun PreparedStatement.bind(args: List<Any?>) {
    args.forEachIndexed { index, value ->
        val parameter = index + 1
        when (value) {
            null -> setObject(parameter, null)
            is Instant -> setObject(parameter, OffsetDateTime.ofInstant(value, ZoneOffset.UTC))
            is UUID -> setObject(parameter, value)
            else -> setObject(parameter, value)
        }
    }
}

fun ResultSet.uuid(name: String): UUID = getObject(name, UUID::class.java)

fun ResultSet.instant(name: String): Instant = getObject(name, OffsetDateTime::class.java).toInstant()

fun ResultSet.instantOrNull(name: String): Instant? =
    getObject(name, OffsetDateTime::class.java)?.toInstant()

fun ResultSet.stringOrNull(name: String): String? = getString(name).let { if (wasNull()) null else it }

fun String.dbValue(): String = uppercase(Locale.ROOT).replace('-', '_')

fun String.wireValue(): String = lowercase(Locale.ROOT).replace('_', '-')

fun escapeLike(value: String): String =
    value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
