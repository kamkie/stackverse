package dev.stackverse.backend.message

import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.common.ConflictProblem
import dev.stackverse.backend.common.NotFoundProblem
import dev.stackverse.backend.common.Validator
import dev.stackverse.backend.common.logEvent
import dev.stackverse.backend.common.nowUtc
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

private val KEY_PATTERN = Regex("^[a-z0-9-]+(\\.[a-z0-9-]+)*$")
private val LANGUAGE_PATTERN = Regex("^[a-z]{2}$")

@Service
@Transactional
class MessageService(
    private val repository: MessageRepository,
    private val auditService: AuditService,
) {

    // message CRUD events are diagnostics only (docs/LOGGING.md §5) — the audit trail stays authoritative
    private val log = LoggerFactory.getLogger(javaClass)

    fun create(actor: String, request: MessageRequest): Message {
        val input = validate(request)
        if (repository.existsByKeyAndLanguage(input.key, input.language)) {
            throw ConflictProblem("A message with key '${input.key}' and language '${input.language}' already exists.")
        }
        val now = nowUtc()
        val message = try {
            repository.saveAndFlush(
                Message(
                    key = input.key,
                    language = input.language,
                    text = input.text,
                    description = input.description,
                    createdAt = now,
                    updatedAt = now,
                ),
            )
        } catch (e: DataIntegrityViolationException) {
            throw duplicateConflict(input)
        }
        auditService.record(actor, "message.created", "message", message.id.toString(), snapshot(message))
        logMessageEvent("message_created", "Message created", actor, message)
        return message
    }

    fun update(actor: String, id: UUID, request: MessageRequest): Message {
        val message = repository.findById(id).orElseThrow { NotFoundProblem() }
        val input = validate(request)
        val duplicate = repository.findByKeyAndLanguage(input.key, input.language)
        if (duplicate != null && duplicate.id != message.id) {
            throw ConflictProblem("A message with key '${input.key}' and language '${input.language}' already exists.")
        }
        message.key = input.key
        message.language = input.language
        message.text = input.text
        message.description = input.description
        message.updatedAt = nowUtc()
        try {
            repository.flush()
        } catch (e: DataIntegrityViolationException) {
            throw duplicateConflict(input)
        }
        auditService.record(actor, "message.updated", "message", message.id.toString(), snapshot(message))
        logMessageEvent("message_updated", "Message updated", actor, message)
        return message
    }

    fun delete(actor: String, id: UUID) {
        val message = repository.findById(id).orElseThrow { NotFoundProblem() }
        repository.delete(message)
        auditService.record(actor, "message.deleted", "message", message.id.toString(), snapshot(message))
        logMessageEvent("message_deleted", "Message deleted", actor, message)
    }

    /** The message key is safe to log: validated against `KEY_PATTERN`, so no free-form client text. */
    private fun logMessageEvent(event: String, description: String, actor: String, message: Message) {
        log.logEvent(
            Level.INFO, event, "success", description,
            "actor" to actor,
            "resource_type" to "message",
            "resource_id" to message.id.toString(),
            "message_key" to message.key,
            "language" to message.language,
        )
    }

    /**
     * Flat key → text map for one language (SPEC rule 9): every key of the resolved
     * language plus `en` keys the language is missing, which fall back to their `en` text.
     */
    @Transactional(readOnly = true)
    fun bundle(language: String): Map<String, String> {
        val texts = sortedMapOf<String, String>()
        for (message in repository.findByLanguageIn(setOf(DEFAULT_LANGUAGE, language))) {
            if (message.language == language || message.key !in texts) {
                texts[message.key] = message.text
            }
        }
        return texts
    }

    private fun snapshot(message: Message): Map<String, Any?> = mapOf(
        "key" to message.key,
        "language" to message.language,
        "text" to message.text,
        "description" to message.description,
    )

    private data class ValidatedMessage(val key: String, val language: String, val text: String, val description: String?)

    private fun duplicateConflict(input: ValidatedMessage): ConflictProblem =
        ConflictProblem("A message with key '${input.key}' and language '${input.language}' already exists.")

    private fun validate(request: MessageRequest): ValidatedMessage {
        val validator = Validator()
        val key = request.key?.trim().orEmpty()
        validator.check(key.matches(KEY_PATTERN) && key.length <= 150, "key", "validation.message.key.invalid")
        val language = request.language?.trim().orEmpty()
        validator.check(language.matches(LANGUAGE_PATTERN), "language", "validation.message.language.invalid")
        val text = request.text.orEmpty()
        validator.check(text.isNotEmpty(), "text", "validation.message.text.required")
        validator.check(text.length <= 2000, "text", "validation.message.text.too-long")
        validator.check((request.description?.length ?: 0) <= 1000, "description", "validation.message.description.too-long")
        validator.throwIfInvalid()
        return ValidatedMessage(key, language, text, request.description)
    }
}
