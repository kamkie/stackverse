package dev.stackverse.backend

import io.ktor.http.HttpStatusCode
import io.ktor.server.routing.get
import org.slf4j.Logger
import org.slf4j.event.Level
import java.sql.Connection
import java.sql.ResultSet
import java.time.Instant

class AccountRepository(private val db: Database, private val audit: AuditRepository, private val logger: Logger) {
    suspend fun recordSeen(username: String): UserAccountResponse = db.transaction {
        val now = nowUtc()
        execute(
            """
            insert into user_accounts (username, first_seen, last_seen, status)
            values (?, ?, ?, 'ACTIVE')
            on conflict (username) do update set last_seen = excluded.last_seen
            """.trimIndent(),
            username, now, now,
        )
        findAccount(username) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
    }

    suspend fun list(q: String?, status: String?, page: Int, size: Int): PageResponse<UserAccountResponse> = db.read {
        var clause = Clause("1 = 1")
        q?.takeIf { it.isNotBlank() }?.let { clause = clause.and("lower(u.username) like ? escape '\\'", "%${escapeLike(it.lowercase())}%") }
        status?.let { clause = clause.and("u.status = ?", it.dbValue()) }
        val total = queryLong("select count(*) from user_accounts u where ${clause.sql}", clause.args)
        val items = query(
            """
            select u.*, (select count(*) from bookmarks b where b.owner = u.username) as bookmark_count
            from user_accounts u
            where ${clause.sql}
            order by u.last_seen desc
            limit ? offset ?
            """.trimIndent(),
            clause.args + listOf(size, page * size),
        ) { it.toUserAccount() }
        PageResponse(items, page, size, total, pages(total, size))
    }

    suspend fun get(username: String): UserAccountResponse = db.read {
        findAccount(username) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
    }

    suspend fun setStatus(actor: String, username: String, request: UserStatusRequest): UserAccountResponse = db.transaction {
        val account = findAccount(username) ?: throw ApiProblem(HttpStatusCode.NotFound, "Not Found")
        val validator = Validator()
        validator.check(request.status in setOf("active", "blocked"), "status", "validation.user.status.invalid")
        if (request.status == "blocked") {
            validator.check(!request.reason.isNullOrBlank(), "reason", "validation.block.reason.required")
            validator.check((request.reason?.length ?: 0) <= 1000, "reason", "validation.block.reason.too-long")
        }
        validator.throwIfInvalid()
        if (request.status == "blocked" && username == actor) {
            throw ApiProblem(HttpStatusCode.Conflict, "Conflict", detail = "Admins cannot block themselves.")
        }
        if (request.status == "blocked") {
            execute("update user_accounts set status = 'BLOCKED', blocked_reason = ? where username = ?", request.reason, username)
            audit.record(this, actor, "user.blocked", "user", username, mapOf("reason" to request.reason))
            logger.logEvent(
                Level.INFO,
                "user_blocked",
                "success",
                "User account blocked",
                "actor" to actor,
                "resource_type" to "user",
                "resource_id" to username,
            )
        } else {
            execute("update user_accounts set status = 'ACTIVE', blocked_reason = null where username = ?", username)
            audit.record(this, actor, "user.unblocked", "user", username)
            logger.logEvent(
                Level.INFO,
                "user_unblocked",
                "success",
                "User account unblocked",
                "actor" to actor,
                "resource_type" to "user",
                "resource_id" to username,
            )
        }
        findAccount(username) ?: account
    }

    private fun Connection.findAccount(username: String): UserAccountResponse? =
        query(
            """
            select u.*, (select count(*) from bookmarks b where b.owner = u.username) as bookmark_count
            from user_accounts u
            where u.username = ?
            """.trimIndent(),
            listOf(username),
        ) { it.toUserAccount() }.firstOrNull()

    private fun ResultSet.toUserAccount() = UserAccountResponse(
        username = getString("username"),
        firstSeen = instant("first_seen"),
        lastSeen = instant("last_seen"),
        status = getString("status").wireValue(),
        blockedReason = stringOrNull("blocked_reason"),
        bookmarkCount = getLong("bookmark_count"),
    )
}
