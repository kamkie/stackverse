package dev.stackverse.backend.moderation

import dev.stackverse.backend.audit.AuditService
import dev.stackverse.backend.bookmark.BookmarkService
import dev.stackverse.backend.config.EventLogger
import dev.stackverse.backend.message.MessageService
import dev.stackverse.backend.persistence.Bookmark
import dev.stackverse.backend.persistence.Report
import dev.stackverse.backend.support.ApiError
import dev.stackverse.backend.support.TimeSource
import grails.testing.gorm.DataTest
import org.springframework.jdbc.core.JdbcTemplate
import spock.lang.Specification

import java.sql.Timestamp
import java.time.Instant

class ModerationServiceSpec extends Specification implements DataTest {
    private static final Instant NOW = Instant.parse('2026-07-11T12:34:56.123456Z')

    TimeSource timeSource = Stub() {
        now() >> NOW
    }
    MessageService messageService = Stub()
    BookmarkService bookmarkService = Mock()
    AuditService auditService = Mock()
    EventLogger eventLogger = Mock()
    CapturingJdbcTemplate jdbcTemplate = new CapturingJdbcTemplate()
    ModerationService service = new ModerationService(
        jdbcTemplate: jdbcTemplate,
        timeSource: timeSource,
        messageService: messageService,
        bookmarkService: bookmarkService,
        auditService: auditService,
        eventLogger: eventLogger
    )

    @Override
    Class<?>[] getDomainClassesToMock() {
        [Bookmark, Report] as Class<?>[]
    }

    void 'report lists preserve reporter ownership and moderation queue ordering'() {
        given:
        Report newest = report('alice', 'open', NOW.minusSeconds(10))
        Report oldest = report('alice', 'open', NOW.minusSeconds(30))
        report('alice', 'dismissed', NOW.minusSeconds(20))
        report('bob', 'open', NOW.minusSeconds(40))

        when:
        Map mine = service.listMine('alice', [status: 'open', page: '0', size: '10'])
        Map queue = service.listQueue([status: 'open', page: '0', size: '10'])

        then:
        mine.items*.id == [newest.id.toString(), oldest.id.toString()]
        mine.totalItems == 2L
        queue.items*.createdAt == queue.items*.createdAt.sort()
        queue.totalItems == 3L
    }

    void 'reporters can update only their own open report and logs contain identifiers rather than comment text'() {
        given:
        Report existing = report('alice', 'open', NOW.minusSeconds(10))

        when:
        Map updated = service.updateMine(existing.id, [reason: 'offensive', comment: 'sensitive report text'], 'alice')

        then:
        updated.reason == 'offensive'
        updated.comment == 'sensitive report text'
        1 * eventLogger.info(
            'report_updated',
            'success',
            'Report updated',
            { Map values -> values == [actor: 'alice', resource_type: 'report', resource_id: existing.id.toString()] }
        )
        0 * auditService._
    }

    void 'report update masks foreign ownership and rejects resolved state'() {
        given:
        Report existing = report('alice', status, NOW.minusSeconds(10))

        when:
        service.updateMine(existing.id, [reason: 'spam', comment: null], caller)

        then:
        ApiError error = thrown()
        error.status == expectedStatus
        0 * eventLogger._

        where:
        status      | caller  || expectedStatus
        'open'      | 'bob'   || 404
        'dismissed' | 'alice' || 409
    }

    void 'reporters can withdraw their own open report without creating audit history'() {
        given:
        Report existing = report('alice', 'open', NOW.minusSeconds(10))

        when:
        service.withdraw(existing.id, 'alice')

        then:
        Report.get(existing.id) == null
        1 * eventLogger.info(
            'report_withdrawn',
            'success',
            'Report withdrawn',
            { Map values -> values == [actor: 'alice', resource_type: 'report', resource_id: existing.id.toString()] }
        )
        0 * auditService._
    }

    void 'reopening clears every resolution field and records the moderator action'() {
        given:
        Report existing = report('alice', 'dismissed', NOW.minusSeconds(10))
        existing.resolvedBy = 'moderator'
        existing.resolvedAt = NOW.minusSeconds(5)
        existing.resolutionNote = 'old note'
        existing.save(failOnError: true, flush: true)

        when:
        Map reopened = service.resolve(existing.id, [resolution: 'open', note: 'ignored'], 'moderator')

        then:
        reopened.status == 'open'
        !reopened.containsKey('resolvedBy') || reopened.resolvedBy == null
        reopened.resolvedAt == null
        reopened.resolutionNote == null
        1 * auditService.record('moderator', 'report.reopened', 'report', existing.id.toString())
        1 * eventLogger.info(
            'report_reopened',
            'success',
            'Report reopened',
            { Map values -> values == [actor: 'moderator', resource_type: 'report', resource_id: existing.id.toString()] }
        )
    }

    void 'reopening conflicts when the reporter already has another open report on the bookmark'() {
        given:
        UUID bookmarkId = UUID.randomUUID()
        Report older = report('alice', 'dismissed', NOW.minusSeconds(20), bookmarkId)
        report('alice', 'open', NOW.minusSeconds(10), bookmarkId)

        when:
        service.resolve(older.id, [resolution: 'open', note: null], 'moderator')

        then:
        ApiError error = thrown()
        error.status == 409
        error.message == 'The reporter already has another open report on this bookmark.'
        Report.get(older.id).status == 'dismissed'
        0 * auditService._
        0 * eventLogger._
    }

    void 'actioning a report hides its bookmark and parameterizes sibling resolution'() {
        given:
        Bookmark bookmark = bookmark('public', 'active')
        Report existing = report('alice', 'open', NOW.minusSeconds(10), bookmark.id)

        when:
        Map resolved = service.resolve(existing.id, [resolution: 'actioned', note: 'policy note'], 'moderator')

        then:
        resolved.status == 'actioned'
        resolved.resolvedBy == 'moderator'
        resolved.resolutionNote == 'policy note'
        Bookmark.get(bookmark.id).status == 'hidden'
        Bookmark.get(bookmark.id).updatedAt == NOW
        jdbcTemplate.updateSql.contains("where bookmark_id = ? and status = 'open' and id <> ?")
        jdbcTemplate.updateArguments == [
            'moderator',
            Timestamp.from(NOW),
            'policy note',
            bookmark.id,
            existing.id
        ]
        1 * auditService.record('moderator', 'report.resolved', 'report', existing.id.toString(), [resolution: 'actioned'])
        1 * eventLogger.info(
            'report_resolved',
            'success',
            'Report resolved',
            { Map values -> values == [actor: 'moderator', resource_type: 'report', resource_id: existing.id.toString()] }
        )
    }

    void 'moderators can hide or restore a bookmark with audit detail while logs omit the note'() {
        given:
        Bookmark existing = bookmark('public', 'hidden')
        Map returned = [id: existing.id.toString(), status: 'active']

        when:
        Map result = service.setBookmarkStatus(existing.id, [status: 'active', note: 'sensitive note'], 'moderator')

        then:
        result == returned
        Bookmark.get(existing.id).status == 'active'
        Bookmark.get(existing.id).updatedAt == NOW
        1 * bookmarkService.find(existing.id) >> returned
        1 * auditService.record(
            'moderator',
            'bookmark.status-changed',
            'bookmark',
            existing.id.toString(),
            [status: 'active', note: 'sensitive note']
        )
        1 * eventLogger.info(
            'bookmark_status_changed',
            'success',
            'Bookmark status changed',
            { Map values -> values == [actor: 'moderator', resource_type: 'bookmark', resource_id: existing.id.toString()] }
        )
    }

    private static Report report(String reporter, String status, Instant createdAt, UUID bookmarkId = UUID.randomUUID()) {
        Report report = new Report(
            bookmarkId: bookmarkId,
            reporter: reporter,
            reason: 'spam',
            status: status,
            createdAt: createdAt
        )
        report.id = UUID.randomUUID()
        report.save(failOnError: true, flush: true)
        report
    }

    private static Bookmark bookmark(String visibility, String status) {
        Bookmark bookmark = new Bookmark(
            owner: 'owner',
            url: 'https://example.com',
            title: 'Example',
            visibility: visibility,
            status: status,
            createdAt: NOW.minusSeconds(60),
            updatedAt: NOW.minusSeconds(60)
        )
        bookmark.id = UUID.randomUUID()
        bookmark.save(failOnError: true, flush: true)
        bookmark
    }

    private static class CapturingJdbcTemplate extends JdbcTemplate {
        String updateSql
        List updateArguments = []

        @Override
        int update(String sql, Object... args) {
            updateSql = sql
            updateArguments = args.toList()
            1
        }
    }
}
