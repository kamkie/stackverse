package dev.stackverse.backend.message

import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.config.EventLogger
import dev.stackverse.backend.support.ApiError
import dev.stackverse.backend.support.Paging
import dev.stackverse.backend.support.SqlLike
import dev.stackverse.backend.support.SqlRows
import dev.stackverse.backend.support.TimeSource
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional

import java.nio.file.Files
import java.nio.file.Path
import java.sql.Timestamp
import java.util.regex.Pattern

class MessageService implements ApplicationRunner {
    private static final Pattern KEY = ~/^[a-z0-9-]+(\.[a-z0-9-]+)*$/
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
        List clauses = []
        List args = []
        if (params.key) {
            clauses << "key = ?"
            args << params.key.toString()
        }
        if (params.language) {
            clauses << "language = ?"
            args << params.language.toString()
        }
        if (params.q) {
            clauses << "(lower(key) like ? escape '\\' or lower(text) like ? escape '\\')"
            String q = "%${SqlLike.escape(params.q.toString().toLowerCase(Locale.ROOT))}%"
            args << q
            args << q
        }
        String where = clauses ? "where ${clauses.join(' and ')}" : ""
        Long total = jdbcTemplate.queryForObject("select count(*) from messages ${where}", Long, args as Object[])
        List pageArgs = args + [size, page * size]
        List items = jdbcTemplate.query("""
            select id, key, language, text, description, created_at, updated_at
            from messages
            ${where}
            order by key asc, language asc
            limit ? offset ?
        """, { rs, rowNum -> messageRow(rs) }, pageArgs as Object[])
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
        List rows = jdbcTemplate.query("select id, key, language, text, description, created_at, updated_at from messages where id = ?",
            { rs, rowNum -> messageRow(rs) }, id)
        if (!rows) {
            throw ApiError.notFound()
        }
        rows[0]
    }

    @Transactional
    Map create(Map input, String actor, String explicitLang, String acceptLanguage) {
        validate(input, explicitLang, acceptLanguage)
        UUID id = UUID.randomUUID()
        def now = timeSource.now()
        try {
            jdbcTemplate.update("""
                insert into messages (id, key, language, text, description, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?, ?)
            """, id, input.key, input.language, input.text, input.description, Timestamp.from(now), Timestamp.from(now))
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            throw ApiError.conflict("A message with this key and language already exists.")
        }
        auditService.record(actor, "message.created", "message", id.toString(), [key: input.key, language: input.language])
        eventLogger.info("message_created", "success", "Message created", [actor: actor, resource_type: "message", resource_id: id.toString()])
        get(id)
    }

    @Transactional
    Map update(UUID id, Map input, String actor, String explicitLang, String acceptLanguage) {
        get(id)
        validate(input, explicitLang, acceptLanguage)
        def now = timeSource.now()
        try {
            jdbcTemplate.update("""
                update messages set key = ?, language = ?, text = ?, description = ?, updated_at = ?
                where id = ?
            """, input.key, input.language, input.text, input.description, Timestamp.from(now), id)
        } catch (org.springframework.dao.DuplicateKeyException ignored) {
            throw ApiError.conflict("A message with this key and language already exists.")
        }
        auditService.record(actor, "message.updated", "message", id.toString(), [key: input.key, language: input.language])
        eventLogger.info("message_updated", "success", "Message updated", [actor: actor, resource_type: "message", resource_id: id.toString()])
        get(id)
    }

    @Transactional
    void delete(UUID id, String actor) {
        get(id)
        jdbcTemplate.update("delete from messages where id = ?", id)
        auditService.record(actor, "message.deleted", "message", id.toString())
        eventLogger.info("message_deleted", "success", "Message deleted", [actor: actor, resource_type: "message", resource_id: id.toString()])
    }

    private void validate(Map input, String explicitLang, String acceptLanguage) {
        List errors = []
        if (!(input.key instanceof String) || !(input.key ==~ KEY) || input.key.size() > 150) {
            errors << validationError("key", "validation.message.key.invalid", explicitLang, acceptLanguage)
        }
        if (!(input.language instanceof String) || !(input.language ==~ LANG)) {
            errors << validationError("language", "validation.message.language.invalid", explicitLang, acceptLanguage)
        }
        if (!(input.text instanceof String) || input.text.size() == 0) {
            errors << validationError("text", "validation.message.text.required", explicitLang, acceptLanguage)
        } else if (input.text.size() > 2000) {
            errors << validationError("text", "validation.message.text.too-long", explicitLang, acceptLanguage)
        }
        if (input.description != null && input.description.toString().size() > 1000) {
            errors << validationError("description", "validation.message.description.too-long", explicitLang, acceptLanguage)
        }
        if (errors) {
            throw ApiError.badRequest("Validation failed.", errors)
        }
    }

    private String lookup(String key, String language) {
        List rows = jdbcTemplate.queryForList("select text from messages where key = ? and language = ?", String, key, language)
        rows ? rows[0] : null
    }

    private Set<String> supportedLanguages() {
        jdbcTemplate.queryForList("select distinct language from messages", String) as Set<String>
    }

    private Map<String, String> messagesForLanguage(String language) {
        LinkedHashMap values = new LinkedHashMap()
        jdbcTemplate.queryForList("select key, text from messages where language = ? order by key asc", language).each { row ->
            values[row.key as String] = row.text as String
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

    private static Map messageRow(rs) {
        [
            id         : SqlRows.uuid(rs, "id").toString(),
            key        : rs.getString("key"),
            language   : rs.getString("language"),
            text       : rs.getString("text"),
            description: rs.getString("description"),
            createdAt  : SqlRows.rfc3339(SqlRows.instant(rs, "created_at")),
            updatedAt  : SqlRows.rfc3339(SqlRows.instant(rs, "updated_at"))
        ]
    }
}
