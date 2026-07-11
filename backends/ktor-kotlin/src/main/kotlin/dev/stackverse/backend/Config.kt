package dev.stackverse.backend

import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.sql.Connection

data class Config(
    val port: Int,
    val dbHost: String,
    val dbPort: String,
    val dbName: String,
    val dbUser: String,
    val dbPassword: String,
    val issuerUri: String,
    val jwksUri: String,
    val seedMessagesDir: String,
    val logLevel: String,
    val logFormat: String,
) {
    val jdbcUrl: String = "jdbc:postgresql://$dbHost:$dbPort/$dbName"

    companion object {
        fun load(): Config = Config(
            port = env("PORT", "8080").toInt(),
            dbHost = env("DB_HOST", "localhost"),
            dbPort = env("DB_PORT", "5432"),
            dbName = env("DB_NAME", "stackverse"),
            dbUser = env("DB_USER", "stackverse"),
            dbPassword = env("DB_PASSWORD", "stackverse"),
            issuerUri = env("OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse"),
            jwksUri = env("OIDC_JWKS_URI", ""),
            seedMessagesDir = env("SEED_MESSAGES_DIR", "../../spec/messages"),
            logLevel = env("LOG_LEVEL", "info"),
            logFormat = env("LOG_FORMAT", "json"),
        )

        private fun env(name: String, fallback: String): String =
            System.getenv(name)?.takeIf { it.isNotBlank() } ?: fallback
    }
}

class Database(val dataSource: HikariDataSource) {
    suspend fun ready(): Boolean = runCatching {
        read {
            prepareStatement("select 1").use { statement ->
                statement.executeQuery().use { rs -> rs.next() }
            }
        }
    }.getOrDefault(false)

    suspend fun <T> read(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = true
            connection.block()
        }
    }

    suspend fun <T> transaction(block: Connection.() -> T): T = withContext(Dispatchers.IO) {
        dataSource.connection.use { connection ->
            connection.autoCommit = false
            try {
                val result = connection.block()
                connection.commit()
                result
            } catch (error: Throwable) {
                connection.rollback()
                throw error
            }
        }
    }
}
