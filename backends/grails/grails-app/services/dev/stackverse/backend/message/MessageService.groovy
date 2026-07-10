package dev.stackverse.backend.message

import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.config.EventLogger
import dev.stackverse.backend.persistence.Message
import dev.stackverse.backend.support.ApiError
import dev.stackverse.backend.support.Paging
import dev.stackverse.backend.support.SqlLike
import dev.stackverse.backend.support.SqlRows
import dev.stackverse.backend.support.TimeSource
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import grails.gorm.transactions.Transactional

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.util.regex.Pattern

class MessageService implements ApplicationRunner {
    private static final Pattern LANG = ~/^[a-z]{2}$/

    JdbcTemplate jdbcTemplate
    TimeSource timeSource
    AuditService auditService
    EventLogger eventLogger

    @Value('${stackverse.seed.messages-dir}')
    String messagesDir

    @Override
    @Transactional
    void run(ApplicationArguments args) {
        Path dir = Path.of(messagesDir)
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Message seed directory not found: ${dir.toAbsolutePath()}")
        }
        Files.list(dir).withCloseable { stream ->
            stream.filter { it.fileName.toString().endsWith(".json") }.sorted().forEach { Path file ->
                String language = file.fileName.toString().replaceFirst(/\.json$/, "")
                Map entries = new JsonSlurper().parse(file.toFile()) as Map
                int inserted = 0
                def now = timeSource.now()
                entries.each { key, text ->
                    inserted += jdbcTemplate.update("""
                        insert into messages (id, key, language, text, description, created_at, updated_at)
                        values (?, ?, ?, ?, null, ?, ?)
                        on conflict (key, language) do nothing
                    """, UUID.randomUUID(), key.toString(), language, text.toString(), Timestamp.from(now), Timestamp.from(now))
                }
                eventLogger.info("message_seed_imported", "success", "Message seed imported", [
                    language: language,
                    inserted: inserted,
                    skipped : entries.size() - inserted
                ])
            }
        }
    }

    String validationMessage(String messageKey, String explicitLang, String acceptLanguage) {
        String language = resolveLanguage(explicitLang, acceptLanguage)
        String text = lookup(messageKey, language) ?: lookup(messageKey, "en")
        text ?: messageKey
    }

    Map validationError(String field, String messageKey, String explicitLang, String acceptLanguage) {
        [field: field, messageKey: messageKey, message: validationMessage(messageKey, explicitLang, acceptLanguage)]
    }

    String resolveLanguage(String explicitLang, String acceptLanguage) {
        Set<String> supported = supportedLanguages()
        if (explicitLang && supported.contains(explicitLang)) {
            return explicitLang
        }
        parseAcceptLanguage(acceptLanguage).find { supported.contains(it) } ?: "en"
    }

    Map list(Map params) {
        int page = Paging.page(params.page)
        int size = Paging.size(params.size)
        def criteria = Message.where {}
        if (params.key) {
            String keyValue = params.key.toString()
            criteria = criteria.where { key == keyValue }
        }
        if (params.language) {
            String languageValue = params.language.toString()
            criteria = criteria.where { language == languageValue }
        }
        if (params.q) {
            String q = "%${SqlLike.escape(params.q.toString().toLowerCase(Locale.ROOT))}%"
            criteria = criteria.where { ilike('key', q) || ilike('text', q) }
        }
        Long total = criteria.count()
        List items = criteria.list(max: size, offset: page * size) {
            order('key', 'asc')
            order('language', 'asc')
        }.collect { messageMap(it as Message) }
        Paging.resultPage(items, page, size, total)
    }

    Map bundle(String explicitLang, String acceptLanguage) {
        String language = resolveLanguage(explicitLang, acceptLanguage)
        Map<String, String> en = messagesForLanguage("en")
        Map<String, String> requested = language == "en" ? [:] : messagesForLanguage(language)
        LinkedHashMap merged = new LinkedHashMap()
        en.keySet().sort().each { key -> merged[key] = requested[key] ?: en[key] }
        requested.keySet().findAll { !merged.containsKey(it) }.sort().each { key -> merged[key] = requested[key] }
        [language: language, messages: merged]
    }

    Map get(UUID id) {
        Message message = Message.get(id)
        if (!message) {
            throw ApiError.notFound()
        }
        messageMap(message)
    }

    @Transactional
    Map create(Map input, String actor) {
        UUID id = UUID.randomUUID()
        def now = timeSource.now()
        try {
            Message message = new Message(
                key: input.key,
                language: input.language,
                text: input.text,
                description: input.description,
                createdAt: now,
                updatedAt: now
            )
            message.id = id
            message.save(failOnError: true, flush: true)
        } catch (DataIntegrityViolationException ignored) {
            throw ApiError.conflict("A message with this key and language already exists.")
        }
        auditService.record(actor, "message.created", "message", id.toString(), [key: input.key, language: input.language])
        eventLogger.info("message_created", "success", "Message created", [actor: actor, resource_type: "message", resource_id: id.toString()])
        get(id)
    }

    @Transactional
    Map update(UUID id, Map input, String actor) {
        Message message = Message.get(id)
        if (!message) {
            throw ApiError.notFound()
        }
        message.key = input.key
        message.language = input.language
        message.text = input.text
        message.description = input.description
        message.updatedAt = timeSource.now()
        try {
            message.save(failOnError: true, flush: true)
        } catch (DataIntegrityViolationException ignored) {
            throw ApiError.conflict("A message with this key and language already exists.")
        }
        auditService.record(actor, "message.updated", "message", id.toString(), [key: input.key, language: input.language])
        eventLogger.info("message_updated", "success", "Message updated", [actor: actor, resource_type: "message", resource_id: id.toString()])
        get(id)
    }

    @Transactional
    void delete(UUID id, String actor) {
        Message message = Message.get(id)
        if (!message) {
            throw ApiError.notFound()
        }
        message.delete(flush: true)
        auditService.record(actor, "message.deleted", "message", id.toString())
        eventLogger.info("message_deleted", "success", "Message deleted", [actor: actor, resource_type: "message", resource_id: id.toString()])
    }

    protected String lookup(String key, String language) {
        Message.findByKeyAndLanguage(key, language)?.text
    }

    protected Set<String> supportedLanguages() {
        Message.createCriteria().list {
            projections {
                distinct('language')
            }
        } as Set<String>
    }

    protected Map<String, String> messagesForLanguage(String language) {
        LinkedHashMap values = new LinkedHashMap()
        Message.findAllByLanguage(language, [sort: 'key', order: 'asc']).each { Message message ->
            values[message.key] = message.text
        }
        values
    }

    private static List<String> parseAcceptLanguage(String header) {
        if (!header) {
            return []
        }
        header.split(",")
            .collect { raw ->
                List parts = raw.trim().split(";").collect { it.trim() }
                String language = parts[0].toLowerCase(Locale.ROOT).split("-")[0]
                BigDecimal quality = 1.0
                parts.tail().each {
                    if (it.startsWith("q=")) {
                        try {
                            quality = new BigDecimal(it.substring(2))
                        } catch (Exception ignored) {
                            quality = 0
                        }
                    }
                }
                [language: language, quality: quality]
            }
            .findAll { it.language ==~ LANG }
            .sort { a, b -> b.quality <=> a.quality }
            .collect { it.language }
    }

    private static Map messageMap(Message message) {
        [
            id         : message.id.toString(),
            key        : message.key,
            language   : message.language,
            text       : message.text,
            description: message.description,
            createdAt  : SqlRows.rfc3339(message.createdAt),
            updatedAt  : SqlRows.rfc3339(message.updatedAt)
        ]
    }
}
