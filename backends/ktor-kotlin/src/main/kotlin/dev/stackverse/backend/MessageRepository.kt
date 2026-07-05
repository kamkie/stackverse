package dev.stackverse.backend

import com.fasterxml.jackson.databind.ObjectMapper
import io.ktor.http.HttpStatusCode
import org.slf4j.Logger
import org.slf4j.event.Level
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

class MessageRepository(
    private val db: Database,
    private val audit: AuditRepository,
    private val mapper: ObjectMapper,
    private val logger: Logger,
) {
    suspend fun seed(language: String, entries: Map<String, String>): Int = db.transaction {
        val existing = query("select key from messages where language = ?", listOf(language)) { it.getString("key") }.toSet()
        val now = nowUtc()
        var inserted = 0
        entries.filterKeys { it !in existing }.forEach { (key, text) ->
            execute(
                """
                insert into messages (id, key, language, text, description, created_at, updated_at)
                values (?, ?, ?, ?, null, ?, ?)
                """.trimIndent(),
                UUID.randomUUID(), key, language, text, now, now,
            )
            inserted++
        }
        inserted
    }

    suspend fun list(key: String?, language: String?, q: String?, page: Int, size: Int): PageResponse<MessageResponse> = db.read {
        var clause = Clause("1 = 1")
        key?.let { clause = clause.and("key = ?", it) }
        language?.let { clause = clause.and("language = ?", it) }
        q?.takeIf { it.isNotBlank() }?.let {
            val pattern = "%${escapeLike(it.lowercase())}%"
            clause = clause.and("(lower(key) like ? escape '\\' or lower(text) like ? escape '\\')", pattern, pattern)
        }
        val total = queryLong("select count(*) from messages where ${clause.sql}", clause.args)
        val items = query(
            """
            select * from messages
            where ${clause.sql}
            order by key, language
            limit ? offset ?
            """.trimIndent(),
            clause.args + listOf(size, page * size),
        ) { it.toMessage() }
        PageResponse(items, page, size, total, pages(total, size))
    }

    suspend fun get(id: UUID): MessageResponse = db.read {
        findMessage(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
    }

    suspend fun create(actor: String, request: MessageRequest): MessageResponse = db.transaction {
        val input = validate(request)
        if (exists(input.key, input.language)) duplicate(input)
        val now = nowUtc()
        val id = UUID.randomUUID()
        try {
            execute(
                """
                insert into messages (id, key, language, text, description, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent(),
                id, input.key, input.language, input.text, input.description, now, now,
            )
        } catch (error: SQLException) {
            if (error.sqlState == "23505") duplicate(input)
            throw error
        }
        val message = findMessage(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        audit.record(this, actor, "message.created", "message", id.toString(), snapshot(message))
        logMessageEvent("message_created", "Message created", actor, message)
        message
    }

    suspend fun update(actor: String, id: UUID, request: MessageRequest): MessageResponse = db.transaction {
        val current = findMessage(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        val input = validate(request)
        findByKeyLanguage(input.key, input.language)?.takeIf { it.id != id }?.let { duplicate(input) }
        try {
            execute(
                """
                update messages
                set key = ?, language = ?, text = ?, description = ?, updated_at = ?
                where id = ?
                """.trimIndent(),
                input.key, input.language, input.text, input.description, nowUtc(), id,
            )
        } catch (error: SQLException) {
            if (error.sqlState == "23505") duplicate(input)
            throw error
        }
        val message = findMessage(id) ?: current
        audit.record(this, actor, "message.updated", "message", id.toString(), snapshot(message))
        logMessageEvent("message_updated", "Message updated", actor, message)
        message
    }

    suspend fun delete(actor: String, id: UUID) = db.transaction {
        val message = findMessage(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        execute("delete from messages where id = ?", id)
        audit.record(this, actor, "message.deleted", "message", id.toString(), snapshot(message))
        logMessageEvent("message_deleted", "Message deleted", actor, message)
    }

    suspend fun bundle(language: String): Map<String, String> = db.read {
        val messages = query(
            """
            select * from messages
            where language in (?, ?)
            order by key, language
            """.trimIndent(),
            listOf(DEFAULT_LANGUAGE, language),
        ) { it.toMessage() }
        val result = linkedMapOf<String, String>()
        messages.forEach {
            if (it.language == language || it.key !in result) {
                result[it.key] = it.text
            }
        }
        result.toSortedMap()
    }

    suspend fun supportedLanguages(): Set<String> = db.read {
        query("select distinct language from messages", emptyList()) { it.getString("language") }.toSet()
    }

    suspend fun text(key: String, language: String): String? = db.read {
        query("select text from messages where key = ? and language = ?", listOf(key, language)) { it.getString("text") }.firstOrNull()
    }

    suspend fun texts(keys: Set<String>, languages: Set<String>): Map<Pair<String, String>, String> = db.read {
        if (keys.isEmpty() || languages.isEmpty()) {
            return@read emptyMap()
        }
        val keyPlaceholders = keys.joinToString(", ") { "?" }
        val languagePlaceholders = languages.joinToString(", ") { "?" }
        query(
            "select key, language, text from messages where key in ($keyPlaceholders) and language in ($languagePlaceholders)",
            keys.toList() + languages.toList(),
        ) { (it.getString("key") to it.getString("language")) to it.getString("text") }.toMap()
    }

    private fun Connection.exists(key: String, language: String): Boolean =
        queryLong("select count(*) from messages where key = ? and language = ?", listOf(key, language)) > 0

    private fun Connection.findByKeyLanguage(key: String, language: String): MessageResponse? =
        query("select * from messages where key = ? and language = ?", listOf(key, language)) { it.toMessage() }.firstOrNull()

    private fun Connection.findMessage(id: UUID): MessageResponse? =
        query("select * from messages where id = ?", listOf(id)) { it.toMessage() }.firstOrNull()

    private fun ResultSet.toMessage() = MessageResponse(
        id = uuid("id"),
        key = getString("key"),
        language = getString("language"),
        text = getString("text"),
        description = stringOrNull("description"),
        createdAt = instant("created_at"),
        updatedAt = instant("updated_at"),
    )

    private data class ValidatedMessage(val key: String, val language: String, val text: String, val description: String?)

    private fun validate(request: MessageRequest): ValidatedMessage {
        val validator = Validator()
        val key = request.key?.trim().orEmpty()
        validator.check(key.matches(MESSAGE_KEY_PATTERN) && key.length <= 150, "key", "validation.message.key.invalid")
        val language = request.language?.trim().orEmpty()
        validator.check(language.matches(LANGUAGE_PATTERN), "language", "validation.message.language.invalid")
        val text = request.text.orEmpty()
        validator.check(text.isNotEmpty(), "text", "validation.message.text.required")
        validator.check(text.length <= 2000, "text", "validation.message.text.too-long")
        validator.check((request.description?.length ?: 0) <= 1000, "description", "validation.message.description.too-long")
        validator.throwIfInvalid()
        return ValidatedMessage(key, language, text, request.description)
    }

    private fun duplicate(input: ValidatedMessage): Nothing {
        throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "A message with key '${input.key}' and language '${input.language}' already exists.")
    }

    private fun snapshot(message: MessageResponse) = mapOf(
        "key" to message.key,
        "language" to message.language,
        "text" to message.text,
        "description" to message.description,
    )

    private fun logMessageEvent(event: String, message: String, actor: String, response: MessageResponse) {
        logger.logEvent(
            Level.INFO,
            event,
            "success",
            message,
            "actor" to actor,
            "resource_type" to "message",
            "resource_id" to response.id.toString(),
            "message_key" to response.key,
            "language" to response.language,
        )
    }
}
