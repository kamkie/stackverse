package dev.stackverse.backend.message

import dev.stackverse.backend.common.nowUtc
import org.slf4j.LoggerFactory
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.nameWithoutExtension

@ConfigurationProperties("stackverse.seed")
data class SeedProperties(val messagesDir: String)

/**
 * SPEC rule 12: import the JSON seed files from `spec/messages` (language = filename),
 * inserting only `(key, language)` pairs that don't exist yet — runtime edits by
 * admins survive restarts. Seed inserts are not moderator actions, so they are
 * deliberately not audited.
 */
@Component
class MessageSeeder(
    private val repository: MessageRepository,
    private val objectMapper: ObjectMapper,
    private val properties: SeedProperties,
) : ApplicationRunner {

    private val log = LoggerFactory.getLogger(javaClass)

    @Transactional
    override fun run(args: ApplicationArguments) {
        val dir = Path.of(properties.messagesDir)
        check(Files.isDirectory(dir)) {
            "Message seed directory not found: ${dir.toAbsolutePath()} — set SEED_MESSAGES_DIR to the spec/messages directory"
        }
        for (file in dir.listDirectoryEntries().filter { it.extension == "json" }.sorted()) {
            seedLanguage(file)
        }
    }

    private fun seedLanguage(file: Path) {
        val language = file.nameWithoutExtension
        val entries: Map<String, String> = objectMapper.readValue(Files.readString(file), StringMap::class.java)
        val existing = repository.findByLanguage(language).mapTo(mutableSetOf()) { it.key }
        val now = nowUtc()
        val missing = entries.filterKeys { it !in existing }.map { (key, text) ->
            Message(key = key, language = language, text = text, description = null, createdAt = now, updatedAt = now)
        }
        repository.saveAll(missing)
        log.info("Message seed '{}': {} inserted, {} already present", language, missing.size, entries.size - missing.size)
    }
}

private class StringMap : LinkedHashMap<String, String>()
