package dev.stackverse.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

class BookmarkRepository(private val db: Database) {
    suspend fun create(owner: String, request: BookmarkRequest): BookmarkResponse = db.transaction {
        val input = validateBookmark(request)
        val id = UUID.randomUUID()
        val now = nowUtc()
        execute(
            """
            insert into bookmarks (id, owner, url, title, notes, visibility, status, created_at, updated_at)
            values (?, ?, ?, ?, ?, ?, 'ACTIVE', ?, ?)
            """.trimIndent(),
            id, owner, input.url, input.title, input.notes, input.visibility.dbValue(), now, now,
        )
        replaceTags(id, input.tags)
        findBookmarkOn(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
    }

    suspend fun get(caller: String?, id: UUID): BookmarkResponse = db.read {
        val bookmark = findBookmarkOn(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (bookmark.owner != caller && !(bookmark.visibility == "public" && bookmark.status == "active")) {
            throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        }
        bookmark
    }

    suspend fun update(caller: String, id: UUID, request: BookmarkRequest): BookmarkResponse = db.transaction {
        val current = lockBookmarkOn(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (current.owner != caller) throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        val input = validateBookmark(request)
        if (current.status == "hidden" && input.visibility == "public") {
            throw ApiProblem(
                HttpStatusCode.Conflict,
                "Conflict",
                detail = "This bookmark was hidden by moderation and cannot be made public.",
                detailKey = "error.bookmark.hidden-publish",
            )
        }
        execute(
            """
            update bookmarks
            set url = ?, title = ?, notes = ?, visibility = ?, updated_at = ?
            where id = ?
            """.trimIndent(),
            input.url, input.title, input.notes, input.visibility.dbValue(), nowUtc(), id,
        )
        replaceTags(id, input.tags)
        findBookmarkOn(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
    }

    suspend fun delete(caller: String, id: UUID) = db.transaction {
        val bookmark = findBookmarkOn(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (bookmark.owner != caller) throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        execute("delete from bookmarks where id = ?", id)
    }

    suspend fun listOffset(caller: String?, query: BookmarkListQuery, page: Int, size: Int): PageResponse<BookmarkResponse> = db.read {
        val scope = bookmarkWhere(caller, query)
        val total = queryLong("select count(*) from bookmarks b where ${scope.sql}", scope.args)
        val connection = this
        val items = query(
            """
            select b.* from bookmarks b
            where ${scope.sql}
            order by b.created_at desc, b.id desc
            limit ? offset ?
            """.trimIndent(),
            scope.args + listOf(size, page * size),
        ) { connection.toBookmark(it) }
        PageResponse(items, page, size, total, pages(total, size))
    }

    suspend fun listKeyset(caller: String?, query: BookmarkListQuery, cursor: BookmarkCursor?, size: Int): BookmarkCursorPageResponse = db.read {
        var scope = bookmarkWhere(caller, query)
        if (cursor != null) {
            scope = scope.and(
                "(b.created_at < ? or (b.created_at = ? and b.id < ?))",
                cursor.createdAt,
                cursor.createdAt,
                cursor.id,
            )
        }
        val connection = this
        val fetched = query(
            """
            select b.* from bookmarks b
            where ${scope.sql}
            order by b.created_at desc, b.id desc
            limit ?
            """.trimIndent(),
            scope.args + listOf(size + 1),
        ) { connection.toBookmark(it) }
        val items = fetched.take(size)
        BookmarkCursorPageResponse(items, if (fetched.size > size) BookmarkCursor.of(items.last()).encode() else null)
    }

    suspend fun tags(owner: String): List<TagCountResponse> = db.read {
        query(
            """
            select bt.tag, count(*) as count
            from bookmark_tags bt
            join bookmarks b on b.id = bt.bookmark_id
            where b.owner = ?
            group by bt.tag
            order by count(*) desc, bt.tag
            """.trimIndent(),
            listOf(owner),
        ) { TagCountResponse(it.getString("tag"), it.getLong("count")) }
    }

    fun lockBookmark(connection: Connection, id: UUID): BookmarkResponse? = connection.lockBookmarkOn(id)

    fun findBookmark(connection: Connection, id: UUID): BookmarkResponse? = connection.findBookmarkOn(id)

    private fun Connection.lockBookmarkOn(id: UUID): BookmarkResponse? {
        val connection = this
        return query("select * from bookmarks where id = ? for update", listOf(id)) { connection.toBookmark(it) }.firstOrNull()
    }

    private fun Connection.findBookmarkOn(id: UUID): BookmarkResponse? {
        val connection = this
        return query("select * from bookmarks where id = ?", listOf(id)) { connection.toBookmark(it) }.firstOrNull()
    }

    private fun Connection.toBookmark(rs: ResultSet): BookmarkResponse {
        val id = rs.uuid("id")
        return BookmarkResponse(
            id = id,
            url = rs.getString("url"),
            title = rs.getString("title"),
            notes = rs.stringOrNull("notes"),
            tags = query("select tag from bookmark_tags where bookmark_id = ? order by tag", listOf(id)) { it.getString("tag") },
            visibility = rs.getString("visibility").wireValue(),
            status = rs.getString("status").wireValue(),
            owner = rs.getString("owner"),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.instant("updated_at"),
        )
    }

    private fun Connection.replaceTags(bookmarkId: UUID, tags: List<String>) {
        execute("delete from bookmark_tags where bookmark_id = ?", bookmarkId)
        tags.forEach { tag ->
            execute("insert into bookmark_tags (bookmark_id, tag) values (?, ?)", bookmarkId, tag)
        }
    }

    private fun bookmarkWhere(caller: String?, query: BookmarkListQuery): Clause {
        var scope = if (query.visibility == "public") {
            Clause("b.visibility = 'PUBLIC' and b.status = 'ACTIVE'")
        } else {
            val owner = caller ?: throw ApiProblem(HttpStatusCode.Unauthorized, "Unauthorized", detail = "Authentication is required.")
            var clause = Clause("b.owner = ?", owner)
            if (query.visibility != null) {
                clause = clause.and("b.visibility = ?", query.visibility.dbValue())
            }
            clause
        }
        query.tags.forEach { tag ->
            scope = scope.and("exists (select 1 from bookmark_tags bt where bt.bookmark_id = b.id and bt.tag = ?)", tag)
        }
        query.q?.takeIf { it.isNotBlank() }?.let {
            val pattern = "%${escapeLike(it.lowercase())}%"
            scope = scope.and("(lower(b.title) like ? escape '\\' or lower(coalesce(b.notes, '')) like ? escape '\\')", pattern, pattern)
        }
        return scope
    }

    private data class ValidatedBookmark(val url: String, val title: String, val notes: String?, val tags: List<String>, val visibility: String)

    private fun validateBookmark(request: BookmarkRequest): ValidatedBookmark {
        val validator = Validator()
        val url = request.url?.trim().orEmpty()
        if (url.isEmpty()) {
            validator.reject("url", "validation.url.required")
        } else {
            validator.check(url.length <= 2000 && isHttpUrl(url), "url", "validation.url.invalid")
        }
        val title = request.title?.trim().orEmpty()
        validator.check(title.isNotEmpty(), "title", "validation.title.required")
        validator.check(title.length <= 200, "title", "validation.title.too-long")
        validator.check((request.notes?.length ?: 0) <= 4000, "notes", "validation.notes.too-long")
        val tags = request.tags.orEmpty().map { it.trim().lowercase() }.toCollection(LinkedHashSet())
        validator.check(tags.size <= 10, "tags", "validation.tags.too-many")
        validator.check(tags.all { it.matches(TAG_PATTERN) }, "tags", "validation.tag.invalid")
        val visibility = request.visibility ?: "private"
        validator.check(visibility in VISIBILITIES, "visibility", "validation.visibility.invalid")
        validator.throwIfInvalid()
        return ValidatedBookmark(url, title, request.notes, tags.toList(), visibility)
    }
}
