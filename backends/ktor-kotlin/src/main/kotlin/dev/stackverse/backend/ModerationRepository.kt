package dev.stackverse.backend

import io.ktor.http.HttpStatusCode
import org.slf4j.Logger
import org.slf4j.event.Level
import java.sql.Connection
import java.sql.ResultSet
import java.sql.SQLException
import java.util.UUID

class ModerationRepository(
    private val db: Database,
    private val bookmarks: BookmarkRepository,
    private val audit: AuditRepository,
    private val logger: Logger,
) {
    suspend fun report(reporter: String, bookmarkId: UUID, request: ReportRequest): ReportResponse = db.transaction {
        val bookmark = bookmarks.findBookmark(this, bookmarkId) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (bookmark.visibility != "public" || bookmark.status != "active") throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        val reason = validateReportRequest(request)
        if (openReportExists(bookmarkId, reporter)) {
            throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "You already have an open report on this bookmark.")
        }
        val id = UUID.randomUUID()
        try {
            execute(
                """
                insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
                values (?, ?, ?, ?, ?, 'OPEN', ?)
                """.trimIndent(),
                id, bookmarkId, reporter, reason.dbValue(), request.comment, nowUtc(),
            )
        } catch (error: SQLException) {
            if (error.sqlState == "23505") {
                throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "You already have an open report on this bookmark.")
            }
            throw error
        }
        val report = findReport(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        logger.logEvent(
            Level.INFO,
            "report_created",
            "success",
            "Report created on a public bookmark",
            "actor" to reporter,
            "resource_type" to "report",
            "resource_id" to report.id.toString(),
            "bookmark_id" to bookmarkId.toString(),
            "reason" to report.reason,
        )
        report
    }

    suspend fun listAdmin(status: String, page: Int, size: Int): PageResponse<ReportResponse> = db.read {
        val total = queryLong("select count(*) from reports where status = ?", listOf(status.dbValue()))
        val items = query(
            """
            select * from reports
            where status = ?
            order by created_at asc, id asc
            limit ? offset ?
            """.trimIndent(),
            listOf(status.dbValue(), size, page * size),
        ) { it.toReport() }
        PageResponse(items, page, size, total, pages(total, size))
    }

    suspend fun listMine(reporter: String, status: String?, page: Int, size: Int): PageResponse<ReportResponse> = db.read {
        var clause = Clause("reporter = ?", reporter)
        status?.let { clause = clause.and("status = ?", it.dbValue()) }
        val total = queryLong("select count(*) from reports where ${clause.sql}", clause.args)
        val items = query(
            """
            select * from reports
            where ${clause.sql}
            order by created_at desc, id desc
            limit ? offset ?
            """.trimIndent(),
            clause.args + listOf(size, page * size),
        ) { it.toReport() }
        PageResponse(items, page, size, total, pages(total, size))
    }

    suspend fun updateMine(reporter: String, id: UUID, request: ReportRequest): ReportResponse = db.transaction {
        val report = lockReport(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (report.reporter != reporter) throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (report.status != "open") throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "The report has already been resolved.")
        val reason = validateReportRequest(request)
        execute("update reports set reason = ?, comment = ? where id = ?", reason.dbValue(), request.comment, id)
        val updated = findReport(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        logger.logEvent(
            Level.INFO,
            "report_updated",
            "success",
            "Report updated by its reporter",
            "actor" to reporter,
            "resource_type" to "report",
            "resource_id" to updated.id.toString(),
            "bookmark_id" to updated.bookmarkId.toString(),
            "reason" to updated.reason,
        )
        updated
    }

    suspend fun withdraw(reporter: String, id: UUID) = db.transaction {
        val report = lockReport(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (report.reporter != reporter) throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (report.status != "open") throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "The report has already been resolved.")
        execute("delete from reports where id = ?", id)
        logger.logEvent(
            Level.INFO,
            "report_withdrawn",
            "success",
            "Report withdrawn by its reporter",
            "actor" to reporter,
            "resource_type" to "report",
            "resource_id" to report.id.toString(),
            "bookmark_id" to report.bookmarkId.toString(),
        )
    }

    suspend fun resolve(actor: String, id: UUID, request: ReportResolutionRequest): ReportResponse = db.transaction {
        val resolution = validateResolution(request)
        if (resolution == "actioned") {
            val bookmarkId = query("select bookmark_id from reports where id = ?", listOf(id)) { it.uuid("bookmark_id") }.firstOrNull()
                ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
            bookmarks.lockBookmark(this, bookmarkId)
        }
        val report = lockReport(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (resolution == "open") {
            reopenOne(report, actor)
            return@transaction findReport(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        }
        resolveOne(report, resolution, actor, request.note, autoResolved = false)
        if (resolution == "actioned") {
            hideBookmark(actor, report.bookmarkId, request.note)
            query(
                """
                select * from reports
                where bookmark_id = ? and status = 'OPEN'
                order by id asc
                for update
                """.trimIndent(),
                listOf(report.bookmarkId),
            ) { it.toReport() }
                .filter { it.id != report.id }
                .forEach { resolveOne(it, "actioned", actor, request.note, autoResolved = true) }
        }
        findReport(id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
    }

    suspend fun setBookmarkStatus(actor: String, id: UUID, request: BookmarkStatusRequest): BookmarkResponse = db.transaction {
        val status = request.status
        val validator = Validator()
        validator.check(status in BOOKMARK_STATUSES, "status", "validation.bookmark-status.invalid")
        validator.check((request.note?.length ?: 0) <= 1000, "note", "validation.bookmark-status.note.too-long")
        validator.throwIfInvalid()
        val bookmark = bookmarks.lockBookmark(this, id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        execute("update bookmarks set status = ?, updated_at = ? where id = ?", status!!.dbValue(), nowUtc(), id)
        audit.record(
            this,
            actor,
            "bookmark.status-changed",
            "bookmark",
            id.toString(),
            mapOf("from" to bookmark.status, "to" to status, "note" to request.note),
        )
        logger.logEvent(
            Level.INFO,
            "bookmark_status_changed",
            "success",
            "Bookmark moderation status changed",
            "actor" to actor,
            "resource_type" to "bookmark",
            "resource_id" to id.toString(),
            "from" to bookmark.status,
            "to" to status,
        )
        bookmarks.findBookmark(this, id) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
    }

    private fun Connection.reopenOne(report: ReportResponse, actor: String) {
        if (queryLong(
                """
                select count(*) from reports
                where bookmark_id = ? and reporter = ? and status = 'OPEN' and id <> ?
                """.trimIndent(),
                listOf(report.bookmarkId, report.reporter, report.id),
            ) > 0
        ) {
            throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "The reporter already has another open report on this bookmark.")
        }
        try {
            execute(
                """
                update reports
                set status = 'OPEN', resolved_by = null, resolved_at = null, resolution_note = null
                where id = ?
                """.trimIndent(),
                report.id,
            )
        } catch (error: SQLException) {
            if (error.sqlState == "23505") {
                throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "The reporter already has another open report on this bookmark.")
            }
            throw error
        }
        audit.record(this, actor, "report.reopened", "report", report.id.toString(), mapOf("bookmarkId" to report.bookmarkId.toString()))
        logger.logEvent(
            Level.INFO,
            "report_reopened",
            "success",
            "Report re-opened",
            "actor" to actor,
            "resource_type" to "report",
            "resource_id" to report.id.toString(),
            "bookmark_id" to report.bookmarkId.toString(),
        )
    }

    private fun Connection.resolveOne(report: ReportResponse, resolution: String, actor: String, note: String?, autoResolved: Boolean) {
        val now = nowUtc()
        execute(
            """
            update reports
            set status = ?, resolved_by = ?, resolved_at = ?, resolution_note = ?
            where id = ?
            """.trimIndent(),
            resolution.dbValue(), actor, now, note, report.id,
        )
        audit.record(
            this,
            actor,
            "report.resolved",
            "report",
            report.id.toString(),
            mapOf("bookmarkId" to report.bookmarkId.toString(), "resolution" to resolution, "note" to note, "autoResolved" to autoResolved),
        )
        logger.logEvent(
            Level.INFO,
            "report_resolved",
            "success",
            "Report resolved",
            "actor" to actor,
            "resource_type" to "report",
            "resource_id" to report.id.toString(),
            "bookmark_id" to report.bookmarkId.toString(),
            "resolution" to resolution,
            "auto_resolved" to autoResolved,
        )
    }

    private fun Connection.hideBookmark(actor: String, bookmarkId: UUID, note: String?) {
        val bookmark = bookmarks.findBookmark(this, bookmarkId) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        if (bookmark.status == "hidden") return
        execute("update bookmarks set status = 'HIDDEN', updated_at = ? where id = ?", nowUtc(), bookmarkId)
        audit.record(
            this,
            actor,
            "bookmark.status-changed",
            "bookmark",
            bookmarkId.toString(),
            mapOf("from" to "active", "to" to "hidden", "note" to note),
        )
        logger.logEvent(
            Level.INFO,
            "bookmark_status_changed",
            "success",
            "Bookmark hidden by an actioned report",
            "actor" to actor,
            "resource_type" to "bookmark",
            "resource_id" to bookmarkId.toString(),
            "from" to "active",
            "to" to "hidden",
        )
    }

    private fun Connection.lockReport(id: UUID): ReportResponse? =
        query("select * from reports where id = ? for update", listOf(id)) { it.toReport() }.firstOrNull()

    private fun Connection.findReport(id: UUID): ReportResponse? =
        query("select * from reports where id = ?", listOf(id)) { it.toReport() }.firstOrNull()

    private fun Connection.openReportExists(bookmarkId: UUID, reporter: String): Boolean =
        queryLong("select count(*) from reports where bookmark_id = ? and reporter = ? and status = 'OPEN'", listOf(bookmarkId, reporter)) > 0

    private fun ResultSet.toReport() = ReportResponse(
        id = uuid("id"),
        bookmarkId = uuid("bookmark_id"),
        reporter = getString("reporter"),
        reason = getString("reason").wireValue(),
        comment = stringOrNull("comment"),
        status = getString("status").wireValue(),
        resolvedBy = stringOrNull("resolved_by"),
        resolvedAt = instantOrNull("resolved_at"),
        resolutionNote = stringOrNull("resolution_note"),
        createdAt = instant("created_at"),
    )

    private fun validateReportRequest(request: ReportRequest): String {
        val validator = Validator()
        val reason = request.reason
        validator.check(reason in REPORT_REASONS, "reason", "validation.report.reason.invalid")
        validator.check((request.comment?.length ?: 0) <= 1000, "comment", "validation.report.comment.too-long")
        validator.throwIfInvalid()
        return reason!!
    }

    private fun validateResolution(request: ReportResolutionRequest): String {
        val validator = Validator()
        val resolution = request.resolution
        validator.check(resolution in REPORT_STATUSES, "resolution", "validation.resolution.invalid")
        validator.check((request.note?.length ?: 0) <= 1000, "note", "validation.resolution.note.too-long")
        validator.throwIfInvalid()
        return resolution!!
    }
}
