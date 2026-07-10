package dev.stackverse.backend.persistence

import grails.gorm.transactions.Rollback
import grails.testing.mixin.integration.Integration
import spock.lang.Requires
import spock.lang.Specification

import java.time.Instant

@Integration
@Rollback
@Requires({ env.STACKVERSE_DB_TESTS == 'true' })
class GormPersistenceSpec extends Specification {
    void 'Hibernate persists and queries every application domain against PostgreSQL'() {
        given:
        Instant now = Instant.parse('2026-01-01T00:00:00Z')
        UUID bookmarkId = UUID.randomUUID()
        UUID messageId = UUID.randomUUID()
        UUID reportId = UUID.randomUUID()

        when:
        new UserAccount(
            username: 'gorm-test-user',
            firstSeen: now,
            lastSeen: now,
            status: 'active'
        ).save(failOnError: true, flush: true)
        new Bookmark(
            id: bookmarkId,
            owner: 'gorm-test-user',
            url: 'https://example.com',
            title: 'GORM integration test',
            visibility: 'public',
            status: 'active',
            createdAt: now,
            updatedAt: now
        ).save(failOnError: true, flush: true)
        new Message(
            id: messageId,
            key: "gorm-test-${messageId}",
            language: 'en',
            text: 'GORM integration test',
            createdAt: now,
            updatedAt: now
        ).save(failOnError: true, flush: true)
        new Report(
            id: reportId,
            bookmarkId: bookmarkId,
            reporter: 'gorm-test-reporter',
            reason: 'spam',
            status: 'open',
            createdAt: now
        ).save(failOnError: true, flush: true)

        then:
        UserAccount.countByStatus('active') >= 1
        Bookmark.countByOwner('gorm-test-user') == 1
        Message.findByKeyAndLanguage("gorm-test-${messageId}", 'en').id == messageId
        Report.findByBookmarkIdAndStatus(bookmarkId, 'open').id == reportId
    }
}
