package dev.stackverse.backend.account

import dev.stackverse.backend.support.ApiError
import dev.stackverse.backend.support.Paging
import dev.stackverse.backend.support.SqlLike
import dev.stackverse.backend.support.SqlRows
import dev.stackverse.backend.support.TimeSource
import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.config.EventLogger
import dev.stackverse.backend.persistence.Bookmark
import dev.stackverse.backend.persistence.UserAccount
import org.springframework.transaction.annotation.Transactional

class UserAccountService {
    TimeSource timeSource
    AuditService auditService
    EventLogger eventLogger

    @Transactional
    Map touch(String username) {
        def now = timeSource.now()
        UserAccount account = UserAccount.get(username)
        if (account) {
            account.lastSeen = now
        } else {
            account = new UserAccount(
                username: username,
                firstSeen: now,
                lastSeen: now,
                status: 'active'
            )
        }
        account.save(failOnError: true, flush: true)
        find(username)
    }

    Map find(String username) {
        UserAccount account = UserAccount.get(username)
        account ? accountMap(account, Bookmark.countByOwner(username)) : null
    }

    Map require(String username) {
        Map account = find(username)
        if (!account) {
            throw ApiError.notFound()
        }
        account
    }

    Map list(Map filters, int page, int size) {
        def criteria = UserAccount.where {}
        if (filters.q) {
            String query = "%${SqlLike.escape(filters.q.toString().toLowerCase(Locale.ROOT))}%"
            criteria = criteria.where { ilike('username', query) }
        }
        if (filters.status) {
            String statusValue = filters.status.toString()
            criteria = criteria.where { status == statusValue }
        }
        Long total = criteria.count()
        List items = criteria.list(max: size, offset: page * size) {
            order('lastSeen', 'desc')
            order('username', 'asc')
        }.collect { UserAccount account -> accountMap(account, Bookmark.countByOwner(account.username)) }
        Paging.resultPage(items, page, size, total)
    }

    @Transactional
    Map setStatus(String username, String actor, Map input) {
        UserAccount account = UserAccount.get(username)
        if (!account) {
            throw ApiError.notFound()
        }
        String status = input.status
        String reason = input.reason
        if (status == "blocked") {
            if (username == actor) {
                throw ApiError.conflict("Admins cannot block themselves.")
            }
            account.status = 'blocked'
            account.blockedReason = reason
            account.save(failOnError: true, flush: true)
            auditService.record(actor, "user.blocked", "user", username, [reason: reason])
            eventLogger.info("user_blocked", "success", "User blocked", [actor: actor, resource_type: "user", resource_id: username])
        } else {
            account.status = 'active'
            account.blockedReason = null
            account.save(failOnError: true, flush: true)
            auditService.record(actor, "user.unblocked", "user", username)
            eventLogger.info("user_unblocked", "success", "User unblocked", [actor: actor, resource_type: "user", resource_id: username])
        }
        find(username)
    }

    private static Map accountMap(UserAccount account, Long bookmarkCount) {
        [
            username     : account.username,
            firstSeen    : SqlRows.rfc3339(account.firstSeen),
            lastSeen     : SqlRows.rfc3339(account.lastSeen),
            status       : account.status,
            blockedReason: account.blockedReason,
            bookmarkCount: bookmarkCount
        ]
    }
}
