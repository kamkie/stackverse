package dev.stackverse.backend.audit

import dev.stackverse.backend.support.ApiError
import dev.stackverse.backend.support.TimeSource
import dev.stackverse.backend.persistence.Bookmark
import grails.testing.gorm.DataTest
import groovy.json.JsonSlurper
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import spock.lang.Specification

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.Instant

class AuditServiceSpec extends Specification implements DataTest {
    Instant now = Instant.parse('2026-07-11T12:34:56.123456Z')
    TimeSource timeSource = Stub() {
        now() >> now
    }

    @Override
    Class<?>[] getDomainClassesToMock() {
        [Bookmark] as Class<?>[]
    }

    void 'record persists structured audit detail with a generated id and UTC timestamp'() {
        given:
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate()
        AuditService service = new AuditService(jdbcTemplate: jdbc, timeSource: timeSource)

        when:
        service.record('admin', 'user.blocked', 'user', 'demo', [reason: 'policy'])

        then:
        jdbc.updateSql.contains('insert into audit_entries')
        jdbc.updateArguments[0] instanceof UUID
        jdbc.updateArguments[1..4] == ['admin', 'user.blocked', 'user', 'demo']
        new JsonSlurper().parseText(jdbc.updateArguments[5].toString()) == [reason: 'policy']
        jdbc.updateArguments[6] == Timestamp.from(now)
    }

    void 'list parameterizes every filter and maps JSON rows into a stable page'() {
        given:
        UUID id = UUID.fromString('11111111-1111-1111-1111-111111111111')
        ResultSet row = Stub(ResultSet) {
            getObject('id') >> id
            getString(_ as String) >> { String column -> [
                actor     : 'admin',
                action    : 'report.resolved',
                target_type: 'report',
                target_id : 'report-1',
                detail    : '{"resolution":"dismissed"}'
            ][column] }
            getTimestamp('created_at') >> Timestamp.from(now)
        }
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate(row: row, total: 1L)
        AuditService service = new AuditService(jdbcTemplate: jdbc, timeSource: timeSource)

        when:
        Map page = service.list([
            actor     : 'admin',
            action    : 'report.resolved',
            targetType: 'report',
            targetId  : 'report-1',
            from      : '2026-07-01T00:00:00Z',
            to        : '2026-07-12T00:00:00Z',
            page      : '1',
            size      : '5'
        ])

        then:
        jdbc.countSql.contains('actor = ? and action = ? and target_type = ? and target_id = ? and created_at >= ? and created_at <= ?')
        jdbc.countArguments == [
            'admin',
            'report.resolved',
            'report',
            'report-1',
            Timestamp.from(Instant.parse('2026-07-01T00:00:00Z')),
            Timestamp.from(Instant.parse('2026-07-12T00:00:00Z'))
        ]
        jdbc.pageSql.contains('order by created_at desc, id desc')
        jdbc.pageArguments[-2..-1] == [5, 5]
        page == [
            items: [[
                id        : id.toString(),
                actor     : 'admin',
                action    : 'report.resolved',
                targetType: 'report',
                targetId  : 'report-1',
                detail    : [resolution: 'dismissed'],
                createdAt : now.toString()
            ]],
            page: 1,
            size: 5,
            totalItems: 1L,
            totalPages: 1
        ]
    }

    void 'invalid audit timestamps are localized field validation problems'() {
        given:
        AuditService service = new AuditService(jdbcTemplate: new FakeJdbcTemplate(), timeSource: timeSource)

        when:
        service.list([(field): 'not-an-instant'])

        then:
        ApiError error = thrown()
        error.status == 400
        error.errors == [[
            field     : field,
            messageKey: "validation.${field}.invalid",
            message   : "${field} is invalid."
        ]]

        where:
        field << ['from', 'to']
    }

    private static class FakeJdbcTemplate extends JdbcTemplate {
        String updateSql
        List updateArguments = []
        String countSql
        List countArguments = []
        String pageSql
        List pageArguments = []
        ResultSet row
        Long total = 0L

        @Override
        int update(String sql, Object... args) {
            updateSql = sql
            updateArguments = args.toList()
            1
        }

        @Override
        <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            countSql = sql
            countArguments = args.toList()
            total as T
        }

        @Override
        <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            pageSql = sql
            pageArguments = args.toList()
            row == null ? [] : [rowMapper.mapRow(row, 0)]
        }
    }
}
