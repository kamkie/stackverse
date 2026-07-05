package dev.stackverse.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import org.flywaydb.core.Flyway
import org.slf4j.Logger
import org.slf4j.event.Level
import java.nio.file.Files
import java.nio.file.Path
import java.sql.Connection
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

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

class AppContext(
    val config: Config,
    val mapper: ObjectMapper,
    val database: Database,
    val logger: Logger,
) {
    val audit = AuditRepository(database, mapper)
    val bookmarks = BookmarkRepository(database)
    val messages = MessageRepository(database, audit, mapper, logger)
    val accounts = AccountRepository(database, audit, logger)
    val moderation = ModerationRepository(database, bookmarks, audit, logger)
    val stats = StatsRepository(database)
    val localizer = MessageLocalizer(messages)
    val jwtAuthenticator = JwtAuthenticator(config, mapper, logger)

    fun migrate() {
        val result = Flyway.configure()
            .dataSource(database.dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        result.migrations.forEach {
            logger.logEvent(
                Level.INFO,
                "db_migration_applied",
                "success",
                "Database migration applied",
                "version" to it.version,
                "description" to it.description,
            )
        }
    }

    fun seedMessages() = runBlocking {
        val dir = Path.of(config.seedMessagesDir)
        check(Files.isDirectory(dir)) {
            "Message seed directory not found: ${dir.toAbsolutePath()}. Set SEED_MESSAGES_DIR to the spec/messages directory."
        }
        dir.listDirectoryEntries().filter { it.extension == "json" }.sorted().forEach { file ->
            val language = file.nameWithoutExtension
            val entries: Map<String, String> = mapper.readValue(Files.readString(file))
            val inserted = messages.seed(language, entries)
            logger.logEvent(
                Level.INFO,
                "message_seed_imported",
                "success",
                "Message seed imported",
                "language" to language,
                "inserted" to inserted,
                "skipped" to entries.size - inserted,
            )
        }
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
