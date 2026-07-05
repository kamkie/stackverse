package dev.stackverse.backend.bookmark

import dev.stackverse.backend.config.EventLogger
import dev.stackverse.backend.message.MessageService
import dev.stackverse.backend.support.ApiError
import dev.stackverse.backend.support.BookmarkCursor
import dev.stackverse.backend.support.Paging
import dev.stackverse.backend.support.SqlRows
import dev.stackverse.backend.support.TimeSource
import groovy.transform.CompileDynamic
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.dao.DuplicateKeyException
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional

import java.sql.Timestamp
import java.util.regex.Pattern

@Service
@CompileDynamic
class BookmarkService {
    private static final Pattern TAG = ~/^[a-z0-9-]{1,30}$/

    @Autowired JdbcTemplate jdbcTemplate
    @Autowired TimeSource timeSource
    @Autowired MessageService messageService
    @Autowired EventLogger eventLogger

    Map getVisible(UUID id, String username) {
        Map bookmark = find(id)
        if (!bookmark) {
            throw ApiError.notFound()
        }
        if (bookmark.owner == username || (bookmark.visibility == "public" && bookmark.status == "active")) {
            return bookmark
        }
        throw ApiError.notFound()
    }

    Map listOffset(Map params, String username, String explicitLang, String acceptLanguage) {
        int page = Paging.page(params.page)
        int size = Paging.size(params.size)
        QueryParts parts = listWhere(params, username, explicitLang, acceptLanguage)
        Long total = jdbcTemplate.queryForObject("select count(*) from bookmarks b ${parts.where}", Long, parts.args as Object[])
        List args = parts.args + [size, page * size]
        List items = jdbcTemplate.query("""
            select b.id, b.owner, b.url, b.title, b.notes, b.visibility, b.status, b.created_at, b.updated_at
            from bookmarks b
            ${parts.where}
            order by b.created_at desc, b.id desc
            limit ? offset ?
        """, { rs, rowNum -> bookmarkRow(rs) }, args as Object[])
        [
            items     : items,
            page      : page,
            size      : size,
            totalItems: total,
            totalPages: total == 0 ? 0 : Math.ceil(total / (double) size) as int
        ]
    }

    Map listCursor(Map params, String username, String explicitLang, String acceptLanguage) {
        int size = Paging.size(params.size)
        QueryParts parts = listWhere(params, username, explicitLang, acceptLanguage)
        List args = new ArrayList(parts.args)
        if (params.cursor) {
            BookmarkCursor cursor = BookmarkCursor.decode(params.cursor.toString())
            parts.add("(b.created_at < ? or (b.created_at = ? and b.id < ?))", Timestamp.from(cursor.createdAt), Timestamp.from(cursor.createdAt), cursor.id)
            args = parts.args
        }
        args += [size + 1]
        List items = jdbcTemplate.query("""
            select b.id, b.owner, b.url, b.title, b.notes, b.visibility, b.status, b.created_at, b.updated_at
            from bookmarks b
            ${parts.where}
            order by b.created_at desc, b.id desc
            limit ?
        """, { rs, rowNum -> bookmarkRow(rs) }, args as Object[])
        String nextCursor = null
        if (items.size() > size) {
            Map last = items[size - 1]
            nextCursor = new BookmarkCursor(java.time.Instant.parse(last.createdAt), UUID.fromString(last.id)).encode()
            items = items.take(size)
        }
        [items: items, nextCursor: nextCursor]
    }

    @Transactional
    Map create(Map input, String username, String explicitLang, String acceptLanguage) {
        Map valid = validate(input, explicitLang, acceptLanguage)
        UUID id = UUID.randomUUID()
        def now = timeSource.now()
        jdbcTemplate.update("""
            insert into bookmarks (id, owner, url, title, notes, visibility, status, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, 'active', ?, ?)
        """, id, username, valid.url, valid.title, valid.notes, valid.visibility, Timestamp.from(now), Timestamp.from(now))
        replaceTags(id, valid.tags)
        find(id)
    }

    @Transactional
    Map update(UUID id, Map input, String username, String explicitLang, String acceptLanguage) {
        Map existing = find(id)
        if (!existing || existing.owner != username) {
            throw ApiError.notFound()
        }
        Map valid = validate(input, explicitLang, acceptLanguage)
        if (existing.status == "hidden" && valid.visibility == "public") {
            throw ApiError.conflict(messageService.validationMessage("error.bookmark.hidden-publish", explicitLang, acceptLanguage))
        }
        def now = timeSource.now()
        jdbcTemplate.update("""
            update bookmarks
            set url = ?, title = ?, notes = ?, visibility = ?, updated_at = ?
            where id = ?
        """, valid.url, valid.title, valid.notes, valid.visibility, Timestamp.from(now), id)
        replaceTags(id, valid.tags)
        find(id)
    }

    @Transactional
    void delete(UUID id, String username) {
        Map existing = find(id)
        if (!existing || existing.owner != username) {
            throw ApiError.notFound()
        }
        jdbcTemplate.update("delete from bookmarks where id = ?", id)
    }

    @Transactional
    Map report(UUID bookmarkId, Map input, String username, String explicitLang, String acceptLanguage) {
        Map bookmark = find(bookmarkId)
        if (!bookmark || bookmark.visibility != "public" || bookmark.status != "active") {
            throw ApiError.notFound()
        }
        List errors = []
        if (!(input.reason in ["spam", "offensive", "broken-link", "other"])) {
            errors << messageService.validationError("reason", "validation.report.reason.invalid", explicitLang, acceptLanguage)
        }
        if (input.comment != null && input.comment.toString().size() > 1000) {
            errors << messageService.validationError("comment", "validation.report.comment.too-long", explicitLang, acceptLanguage)
        }
        if (errors) {
            eventLogger.info("input_validation_failed", "failure", "Report validation failed", [actor: username])
            throw ApiError.badRequest("Validation failed.", errors)
        }
        UUID id = UUID.randomUUID()
        def now = timeSource.now()
        try {
            jdbcTemplate.update("""
                insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
                values (?, ?, ?, ?, ?, 'open', ?)
            """, id, bookmarkId, username, input.reason, input.comment, Timestamp.from(now))
        } catch (DuplicateKeyException ignored) {
            throw ApiError.conflict("The caller already has an open report on this bookmark.")
        }
        eventLogger.info("report_created", "success", "Report created", [actor: username, resource_type: "report", resource_id: id.toString()])
        reportRow(id)
    }

    Map tags(String username) {
        List tags = jdbcTemplate.query("""
            select t.tag, count(*) as count
            from bookmark_tags t
            join bookmarks b on b.id = t.bookmark_id
            where b.owner = ?
            group by t.tag
            order by count(*) desc, t.tag asc
        """, { rs, rowNum -> [tag: rs.getString("tag"), count: rs.getLong("count")] }, username)
        [tags: tags]
    }

    Map find(UUID id) {
        List rows = jdbcTemplate.query("""
            select id, owner, url, title, notes, visibility, status, created_at, updated_at
            from bookmarks
            where id = ?
        """, { rs, rowNum -> bookmarkRow(rs) }, id)
        rows ? rows[0] : null
    }

    Map reportRow(UUID id) {
        List rows = jdbcTemplate.query("""
            select id, bookmark_id, reporter, reason, comment, status, resolved_by, resolved_at, resolution_note, created_at
            from reports where id = ?
        """, { rs, rowNum -> [
            id            : SqlRows.uuid(rs, "id").toString(),
            bookmarkId    : SqlRows.uuid(rs, "bookmark_id").toString(),
            reporter      : rs.getString("reporter"),
            reason        : rs.getString("reason"),
            comment       : rs.getString("comment"),
            status        : rs.getString("status"),
            resolvedBy    : rs.getString("resolved_by"),
            resolvedAt    : SqlRows.rfc3339(SqlRows.instant(rs, "resolved_at")),
            resolutionNote: rs.getString("resolution_note"),
            createdAt     : SqlRows.rfc3339(SqlRows.instant(rs, "created_at"))
        ] }, id)
        rows ? rows[0] : null
    }

    private QueryParts listWhere(Map params, String username, String explicitLang, String acceptLanguage) {
        QueryParts parts = new QueryParts()
        List<String> tags = (params.tags ?: []).collect { it.toString() }
        if (tags.any { !(it ==~ TAG) }) {
            throw ApiError.badRequest("Validation failed.", [
                messageService.validationError("tag", "validation.tag.invalid", explicitLang, acceptLanguage)
            ])
        }
        if (params.visibility == "public") {
            parts.add("b.visibility = 'public' and b.status = 'active'")
        } else {
            if (!username) {
                throw ApiError.unauthorized()
            }
            parts.add("b.owner = ?", username)
            if (params.visibility in ["private", "public"]) {
                parts.add("b.visibility = ?", params.visibility)
            } else if (params.visibility) {
                throw ApiError.badRequest("Validation failed.")
            }
        }
        if (params.q) {
            parts.add("(lower(b.title) like ? escape '\\' or lower(coalesce(b.notes, '')) like ? escape '\\')",
                "%${escapeLike(params.q.toString().toLowerCase(Locale.ROOT))}%",
                "%${escapeLike(params.q.toString().toLowerCase(Locale.ROOT))}%")
        }
        tags.eachWithIndex { tag, index ->
            parts.add("exists (select 1 from bookmark_tags bt${index} where bt${index}.bookmark_id = b.id and bt${index}.tag = ?)", tag)
        }
        parts
    }

    private Map validate(Map input, String explicitLang, String acceptLanguage) {
        List errors = []
        String url = input.url instanceof String ? input.url.trim() : null
        String title = input.title instanceof String ? input.title : null
        String notes = input.notes instanceof String ? input.notes : null
        String visibility = input.visibility ?: "private"
        List tags = normalizeTags(input.tags)

        if (!url) {
            errors << messageService.validationError("url", "validation.url.required", explicitLang, acceptLanguage)
        } else if (url.size() > 2000 || !validHttpUrl(url)) {
            errors << messageService.validationError("url", "validation.url.invalid", explicitLang, acceptLanguage)
        }
        if (!title) {
            errors << messageService.validationError("title", "validation.title.required", explicitLang, acceptLanguage)
        } else if (title.size() > 200) {
            errors << messageService.validationError("title", "validation.title.too-long", explicitLang, acceptLanguage)
        }
        if (notes != null && notes.size() > 4000) {
            errors << messageService.validationError("notes", "validation.notes.too-long", explicitLang, acceptLanguage)
        }
        if (!(visibility in ["private", "public"])) {
            errors << [field: "visibility", messageKey: "validation.visibility.invalid", message: "Visibility is invalid."]
        }
        if (tags.size() > 10) {
            errors << messageService.validationError("tags", "validation.tags.too-many", explicitLang, acceptLanguage)
        }
        if (tags.any { !(it ==~ TAG) }) {
            errors << messageService.validationError("tags", "validation.tag.invalid", explicitLang, acceptLanguage)
        }
        if (errors) {
            eventLogger.info("input_validation_failed", "failure", "Bookmark validation failed")
            throw ApiError.badRequest("Validation failed.", errors)
        }
        [url: url, title: title, notes: notes, visibility: visibility, tags: tags]
    }

    private static boolean validHttpUrl(String value) {
        try {
            URI uri = new URI(value)
            uri.absolute && uri.scheme in ["http", "https"] && uri.host
        } catch (Exception ignored) {
            false
        }
    }

    private static List<String> normalizeTags(Object value) {
        if (value == null) {
            return []
        }
        Collection raw = value instanceof Collection ? value : [value]
        LinkedHashSet normalized = new LinkedHashSet()
        raw.each {
            if (it != null) {
                normalized << it.toString().trim().toLowerCase(Locale.ROOT)
            }
        }
        normalized.findAll { it != "" } as List
    }

    private void replaceTags(UUID id, List<String> tags) {
        jdbcTemplate.update("delete from bookmark_tags where bookmark_id = ?", id)
        tags.each { jdbcTemplate.update("insert into bookmark_tags (bookmark_id, tag) values (?, ?)", id, it) }
    }

    private Map bookmarkRow(rs) {
        UUID id = SqlRows.uuid(rs, "id")
        [
            id        : id.toString(),
            url       : rs.getString("url"),
            title     : rs.getString("title"),
            notes     : rs.getString("notes"),
            tags      : tagsFor(id),
            visibility: rs.getString("visibility"),
            status    : rs.getString("status"),
            owner     : rs.getString("owner"),
            createdAt : SqlRows.rfc3339(SqlRows.instant(rs, "created_at")),
            updatedAt : SqlRows.rfc3339(SqlRows.instant(rs, "updated_at"))
        ]
    }

    private List<String> tagsFor(UUID id) {
        jdbcTemplate.queryForList("select tag from bookmark_tags where bookmark_id = ? order by tag asc", String, id)
    }

    private static String escapeLike(String value) {
        value.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }

    private static class QueryParts {
        final List<String> clauses = []
        final List args = []

        void add(String clause, Object... values) {
            clauses << clause
            args.addAll(values as List)
        }

        String getWhere() {
            clauses ? "where ${clauses.join(' and ')}" : ""
        }
    }
}
