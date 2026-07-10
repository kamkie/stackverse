package dev.stackverse.backend.persistence

import dev.stackverse.backend.command.TestJwtConfiguration
import grails.testing.mixin.integration.Integration
import org.springframework.context.annotation.Import
import spock.lang.Requires
import spock.lang.Specification

import java.time.Instant

@Integration
@Import(TestJwtConfiguration)
@Requires({ env.STACKVERSE_DB_TESTS == 'true' })
class GormPersistenceSpec extends Specification {
    void 'Hibernate persists and queries every application domain against PostgreSQL'() {
        given:
        Instant now = Instant.parse('2026-01-01T00:00:00Z')
        UUID bookmarkId = UUID.randomUUID()
        UUID messageId = UUID.randomUUID()
        UUID reportId = UUID.randomUUID()
        String username = "gorm-test-${bookmarkId}"

        when:
        Bookmark.withTransaction {
            new UserAccount(
                username: username,
                firstSeen: now,
                lastSeen: now,
                status: 'active'
            ).save(failOnError: true, flush: true)
            Bookmark bookmark = new Bookmark(
                owner: username,
                url: 'https://example.com',
                title: 'GORM integration test',
                visibility: 'public',
                status: 'active',
                createdAt: now,
                updatedAt: now
            )
            bookmark.id = bookmarkId
            bookmark.save(failOnError: true, flush: true)
            Message message = new Message(
                key: "gorm-test-${messageId}",
                language: 'en',
                text: 'GORM integration test',
                createdAt: now,
                updatedAt: now
            )
            message.id = messageId
            message.save(failOnError: true, flush: true)
            Report report = new Report(
                bookmarkId: bookmarkId,
                reporter: 'gorm-test-reporter',
                reason: 'spam',
                status: 'open',
                createdAt: now
            )
            report.id = reportId
            report.save(failOnError: true, flush: true)
        }
        Map persisted = Bookmark.withTransaction {
            [
                userStatus   : UserAccount.get(username).status,
                bookmarkCount: Bookmark.countByOwner(username),
                messageId    : Message.findByKeyAndLanguage("gorm-test-${messageId}", 'en').id,
                reportId     : Report.findByBookmarkIdAndStatus(bookmarkId, 'open').id
            ]
        }

        then:
        persisted == [
            userStatus   : 'active',
            bookmarkCount: 1,
            messageId    : messageId,
            reportId     : reportId
        ]

        cleanup:
        Bookmark.withTransaction {
            Report.get(reportId)?.delete(flush: true)
            Bookmark.get(bookmarkId)?.delete(flush: true)
            Message.get(messageId)?.delete(flush: true)
            UserAccount.get(username)?.delete(flush: true)
        }
    }
}
