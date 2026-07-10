package dev.stackverse.backend.bookmark

import dev.stackverse.backend.config.EventLogger
import dev.stackverse.backend.message.MessageService
import dev.stackverse.backend.persistence.Bookmark
import dev.stackverse.backend.persistence.Report
import dev.stackverse.backend.support.ApiError
import dev.stackverse.backend.support.BookmarkCursor
import dev.stackverse.backend.support.Paging
import dev.stackverse.backend.support.ReportRows
import dev.stackverse.backend.support.SqlLike
import dev.stackverse.backend.support.SqlRows
import dev.stackverse.backend.support.TimeSource
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.jdbc.core.JdbcTemplate
import grails.gorm.transactions.Transactional

import java.sql.Timestamp
import java.util.regex.Pattern

class BookmarkService {
    private static final Pattern TAG = ~/^[a-z0-9-]{1,30}$/

    JdbcTemplate jdbcTemplate
    TimeSource timeSource
    MessageService messageService
    EventLogger eventLogger

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
        Paging.resultPage(withTags(items), page, size, total)
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
        [items: withTags(items), nextCursor: nextCursor]
    }

    @Transactional
    Map create(Map valid, String username) {
        UUID id = UUID.randomUUID()
        def now = timeSource.now()
        Bookmark bookmark = new Bookmark(
            owner: username,
            url: valid.url,
            title: valid.title,
            notes: valid.notes,
            visibility: valid.visibility,
            status: 'active',
            createdAt: now,
            updatedAt: now
        )
        bookmark.id = id
        bookmark.save(failOnError: true, flush: true)
        replaceTags(id, valid.tags)
        find(id)
    }

    @Transactional
    Map update(UUID id, Map valid, String username, String explicitLang, String acceptLanguage) {
        Bookmark existing = Bookmark.get(id)
        if (!existing || existing.owner != username) {
            throw ApiError.notFound()
        }
        if (existing.status == "hidden" && valid.visibility == "public") {
            throw ApiError.conflict(messageService.validationMessage("error.bookmark.hidden-publish", explicitLang, acceptLanguage))
        }
        existing.url = valid.url
        existing.title = valid.title
        existing.notes = valid.notes
        existing.visibility = valid.visibility
        existing.updatedAt = timeSource.now()
        existing.save(failOnError: true, flush: true)
        replaceTags(id, valid.tags)
        find(id)
    }

    @Transactional
    void delete(UUID id, String username) {
        Bookmark existing = Bookmark.get(id)
        if (!existing || existing.owner != username) {
            throw ApiError.notFound()
        }
        existing.delete(flush: true)
    }

    @Transactional
    Map report(UUID bookmarkId, Map input, String username) {
        Map bookmark = find(bookmarkId)
        if (!bookmark || bookmark.visibility != "public" || bookmark.status != "active") {
            throw ApiError.notFound()
        }
        UUID id = UUID.randomUUID()
        def now = timeSource.now()
        try {
            Report report = new Report(
                bookmarkId: bookmarkId,
                reporter: username,
                reason: input.reason,
                comment: input.comment,
                status: 'open',
                createdAt: now
            )
            report.id = id
            report.save(failOnError: true, flush: true)
        } catch (DataIntegrityViolationException ignored) {
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
        Bookmark bookmark = Bookmark.get(id)
        bookmark ? withTags([bookmarkMap(bookmark)])[0] : null
    }

    Map reportRow(UUID id) {
        Report report = Report.get(id)
        report ? reportMap(report) : null
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
                "%${SqlLike.escape(params.q.toString().toLowerCase(Locale.ROOT))}%",
                "%${SqlLike.escape(params.q.toString().toLowerCase(Locale.ROOT))}%")
        }
        tags.eachWithIndex { tag, index ->
            parts.add("exists (select 1 from bookmark_tags bt${index} where bt${index}.bookmark_id = b.id and bt${index}.tag = ?)", tag)
        }
        parts
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
            tags      : [],
            visibility: rs.getString("visibility"),
            status    : rs.getString("status"),
            owner     : rs.getString("owner"),
            createdAt : SqlRows.rfc3339(SqlRows.instant(rs, "created_at")),
            updatedAt : SqlRows.rfc3339(SqlRows.instant(rs, "updated_at"))
        ]
    }

    private static Map bookmarkMap(Bookmark bookmark) {
        [
            id        : bookmark.id.toString(),
            url       : bookmark.url,
            title     : bookmark.title,
            notes     : bookmark.notes,
            tags      : [],
            visibility: bookmark.visibility,
            status    : bookmark.status,
            owner     : bookmark.owner,
            createdAt : SqlRows.rfc3339(bookmark.createdAt),
            updatedAt : SqlRows.rfc3339(bookmark.updatedAt)
        ]
    }

    private static Map reportMap(Report report) {
        [
            id            : report.id.toString(),
            bookmarkId    : report.bookmarkId.toString(),
            reporter      : report.reporter,
            reason        : report.reason,
            comment       : report.comment,
            status        : report.status,
            resolvedBy    : report.resolvedBy,
            resolvedAt    : SqlRows.rfc3339(report.resolvedAt),
            resolutionNote: report.resolutionNote,
            createdAt     : SqlRows.rfc3339(report.createdAt)
        ]
    }

    private List<Map> withTags(List<Map> bookmarks) {
        if (!bookmarks) {
            return bookmarks
        }
        List<UUID> ids = bookmarks.collect { UUID.fromString(it.id as String) }
        Map<UUID, List<String>> tagsByBookmark = tagsFor(ids)
        bookmarks.eachWithIndex { Map bookmark, int index ->
            bookmark.tags = tagsByBookmark[ids[index]] ?: []
        }
        bookmarks
    }

    private Map<UUID, List<String>> tagsFor(List<UUID> ids) {
        if (!ids) {
            return [:]
        }
        Map<UUID, List<String>> tagsByBookmark = ids.collectEntries { UUID id -> [(id): []] }
        String placeholders = ids.collect { "?" }.join(", ")
        jdbcTemplate.queryForList("""
            select bookmark_id, tag
            from bookmark_tags
            where bookmark_id in (${placeholders})
            order by bookmark_id asc, tag asc
        """, ids as Object[]).each { row ->
            UUID bookmarkId = row.bookmark_id instanceof UUID ? row.bookmark_id as UUID : UUID.fromString(row.bookmark_id.toString())
            tagsByBookmark[bookmarkId] << row.tag.toString()
        }
        tagsByBookmark
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
