package dev.stackverse.backend.account

import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.config.EventLogger
import dev.stackverse.backend.persistence.Bookmark
import dev.stackverse.backend.persistence.UserAccount
import dev.stackverse.backend.support.ApiError
import dev.stackverse.backend.support.TimeSource
import grails.testing.gorm.DataTest
import spock.lang.Specification

import java.time.Instant

class UserAccountServiceSpec extends Specification implements DataTest {
    AuditService auditService = Mock()
    EventLogger eventLogger = Mock()

    @Override
    Class<?>[] getDomainClassesToMock() {
        [Bookmark, UserAccount] as Class<?>[]
    }

    void 'touch lazily creates an account and advances last-seen without changing first-seen'() {
        given:
        Instant first = Instant.parse('2026-07-11T10:00:00Z')
        Instant second = Instant.parse('2026-07-11T11:00:00Z')
        TimeSource timeSource = Stub() {
            now() >>> [first, second]
        }
        UserAccountService service = service(timeSource)

        when:
        Map created = service.touch('alice')
        Map touched = service.touch('alice')

        then:
        created == [
            username: 'alice',
            firstSeen: first.toString(),
            lastSeen: first.toString(),
            status: 'active',
            blockedReason: null,
            bookmarkCount: 0L
        ]
        touched.firstSeen == first.toString()
        touched.lastSeen == second.toString()
        UserAccount.get('alice').firstSeen == first
        UserAccount.get('alice').lastSeen == second
    }

    void 'require masks an account that does not exist'() {
        given:
        UserAccountService service = service(Stub(TimeSource))

        when:
        service.require('missing')

        then:
        ApiError error = thrown()
        error.status == 404
    }

    void 'an admin cannot block themselves and no audit or log is emitted'() {
        given:
        account('admin', 'active')
        UserAccountService service = service(Stub(TimeSource))

        when:
        service.setStatus('admin', 'admin', [status: 'blocked', reason: 'self'])

        then:
        ApiError error = thrown()
        error.status == 409
        UserAccount.get('admin').status == 'active'
        0 * auditService._
        0 * eventLogger._
    }

    void 'blocking and unblocking persist the reason, record audit history, and log only identifiers'() {
        given:
        account('alice', 'active')
        UserAccountService service = service(Stub(TimeSource))

        when:
        Map blocked = service.setStatus('alice', 'admin', [status: 'blocked', reason: 'sensitive reason'])

        then:
        blocked.status == 'blocked'
        blocked.blockedReason == 'sensitive reason'
        1 * auditService.record('admin', 'user.blocked', 'user', 'alice', [reason: 'sensitive reason'])
        1 * eventLogger.info(
            'user_blocked',
            'success',
            'User blocked',
            { Map values -> values == [actor: 'admin', resource_type: 'user', resource_id: 'alice'] }
        )

        when:
        Map active = service.setStatus('alice', 'admin', [status: 'active', reason: 'ignored'])

        then:
        active.status == 'active'
        active.blockedReason == null
        1 * auditService.record('admin', 'user.unblocked', 'user', 'alice')
        1 * eventLogger.info(
            'user_unblocked',
            'success',
            'User unblocked',
            { Map values -> values == [actor: 'admin', resource_type: 'user', resource_id: 'alice'] }
        )
    }

    private UserAccountService service(TimeSource timeSource) {
        new UserAccountService(timeSource: timeSource, auditService: auditService, eventLogger: eventLogger)
    }

    private static UserAccount account(String username, String status) {
        Instant now = Instant.parse('2026-07-11T10:00:00Z')
        new UserAccount(
            username: username,
            firstSeen: now,
            lastSeen: now,
            status: status
        ).save(failOnError: true, flush: true)
    }
}
