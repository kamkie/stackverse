package dev.stackverse.backend

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import com.zaxxer.hikari.HikariDataSource
import io.ktor.server.application.Application
import io.ktor.server.plugins.di.dependencies
import io.ktor.server.plugins.di.provide
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.slf4j.Logger
import org.slf4j.event.Level
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

fun Application.configureStackverseDependencies(
    config: Config,
    mapper: ObjectMapper,
    logger: Logger,
    dataSourceProvider: () -> HikariDataSource = { hikari(config) },
) {
    dependencies {
        provide<Config> { config }
        provide<ObjectMapper> { mapper }
        provide<Logger> { logger }
        provide<HikariDataSource> { dataSourceProvider() }
        provide<Database>(::Database)
        provide<AuditRepository>(::AuditRepository)
        provide<BookmarkRepository>(::BookmarkRepository)
        provide<MessageRepository>(::MessageRepository)
        provide<AccountRepository>(::AccountRepository)
        provide<ModerationRepository>(::ModerationRepository)
        provide<StatsRepository>(::StatsRepository)
        provide<MessageLocalizer>(::MessageLocalizer)
        provide<JwtAuthenticator>(::JwtAuthenticator)
        provide<StackverseInitializer>(::StackverseInitializer)
    }
}

fun Application.initializeStackverseData() {
    val initializer: StackverseInitializer by dependencies
    initializer.initialize()
}

class StackverseInitializer(
    private val config: Config,
    private val mapper: ObjectMapper,
    private val database: Database,
    private val messages: MessageRepository,
    private val logger: Logger,
) {
    fun initialize() {
        migrate()
        seedMessages()
    }

    private fun migrate() {
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

    private fun seedMessages() = runBlocking {
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
