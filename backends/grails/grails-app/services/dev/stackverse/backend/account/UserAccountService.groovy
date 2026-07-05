package dev.stackverse.backend.account

import dev.stackverse.backend.support.ApiError
import dev.stackverse.backend.support.Paging
import dev.stackverse.backend.support.SqlLike
import dev.stackverse.backend.support.SqlRows
import dev.stackverse.backend.support.TimeSource
import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.config.EventLogger
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.transaction.annotation.Transactional

import java.sql.Timestamp

class UserAccountService {
    JdbcTemplate jdbcTemplate
    TimeSource timeSource
    AuditService auditService
    EventLogger eventLogger

    @Transactional
    Map touch(String username) {
        def now = timeSource.now()
        jdbcTemplate.update("""
            insert into user_accounts (username, first_seen, last_seen, status)
            values (?, ?, ?, 'active')
            on conflict (username) do update set last_seen = excluded.last_seen
        """, username, Timestamp.from(now), Timestamp.from(now))
        find(username)
    }

    Map find(String username) {
        List rows = jdbcTemplate.query("""
            select u.username, u.first_seen, u.last_seen, u.status, u.blocked_reason,
                   (select count(*) from bookmarks b where b.owner = u.username) as bookmark_count
            from user_accounts u
            where u.username = ?
        """, { rs, rowNum -> accountRow(rs) }, username)
        rows ? rows[0] : null
    }

    Map require(String username) {
        Map account = find(username)
        if (!account) {
            throw ApiError.notFound()
        }
        account
    }

    Map list(Map filters, int page, int size) {
        List args = []
        List clauses = []
        if (filters.q) {
            clauses << "lower(u.username) like ? escape '\\'"
            args << "%${SqlLike.escape(filters.q.toString().toLowerCase(Locale.ROOT))}%"
        }
        if (filters.status) {
            clauses << "u.status = ?"
            args << filters.status
        }
        String where = clauses ? "where ${clauses.join(' and ')}" : ""
        Long total = jdbcTemplate.queryForObject("select count(*) from user_accounts u ${where}", Long, args as Object[])
        List pageArgs = args + [size, page * size]
        List items = jdbcTemplate.query("""
            select u.username, u.first_seen, u.last_seen, u.status, u.blocked_reason,
                   (select count(*) from bookmarks b where b.owner = u.username) as bookmark_count
            from user_accounts u
            ${where}
            order by u.last_seen desc, u.username asc
            limit ? offset ?
        """, { rs, rowNum -> accountRow(rs) }, pageArgs as Object[])
        Paging.resultPage(items, page, size, total)
    }

    @Transactional
    Map setStatus(String username, String actor, Map input) {
        Map account = require(username)
        String status = input.status
        String reason = input.reason
        if (!(status in ["active", "blocked"])) {
            throw validation("status", "validation.user-status.invalid", "Status is invalid.")
        }
        if (status == "blocked") {
            if (!reason) {
                throw validation("reason", "validation.block.reason.required", "A reason is required when blocking a user.")
            }
            if (reason.size() > 1000) {
                throw validation("reason", "validation.block.reason.too-long", "Block reason must be at most 1000 characters.")
            }
            if (username == actor) {
                throw ApiError.conflict("Admins cannot block themselves.")
            }
            jdbcTemplate.update("update user_accounts set status = 'blocked', blocked_reason = ? where username = ?", reason, username)
            auditService.record(actor, "user.blocked", "user", username, [reason: reason])
            eventLogger.info("user_blocked", "success", "User blocked", [actor: actor, resource_type: "user", resource_id: username])
        } else {
            jdbcTemplate.update("update user_accounts set status = 'active', blocked_reason = null where username = ?", username)
            auditService.record(actor, "user.unblocked", "user", username)
            eventLogger.info("user_unblocked", "success", "User unblocked", [actor: actor, resource_type: "user", resource_id: username])
        }
        find(username)
    }

    private static Map accountRow(rs) {
        [
            username     : rs.getString("username"),
            firstSeen    : SqlRows.rfc3339(SqlRows.instant(rs, "first_seen")),
            lastSeen     : SqlRows.rfc3339(SqlRows.instant(rs, "last_seen")),
            status       : rs.getString("status"),
            blockedReason: rs.getString("blocked_reason"),
            bookmarkCount: rs.getLong("bookmark_count")
        ]
    }

    private static ApiError validation(String field, String key, String message) {
        ApiError.badRequest("Validation failed.", [[field: field, messageKey: key, message: message]])
    }
}
