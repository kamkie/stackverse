package dev.stackverse.backend.stats

import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.jdbc.core.RowMapper
import spock.lang.Specification

import java.sql.ResultSet
import java.sql.Timestamp
import java.time.LocalDate
import java.time.ZoneOffset

class StatsServiceSpec extends Specification {
    void 'snapshot returns totals, a sparse UTC series zero-filled to thirty days, and top tags'() {
        given:
        ResultSet grails = Stub(ResultSet) {
            getString('tag') >> 'grails'
            getLong('count') >> 4L
        }
        ResultSet gorm = Stub(ResultSet) {
            getString('tag') >> 'gorm'
            getLong('count') >> 2L
        }
        FakeJdbcTemplate jdbc = new FakeJdbcTemplate(
            bookmarkCountsByOffset: [0: 2, 29: 1],
            userCountsByOffset: [1: 3],
            topTagRows: [grails, gorm]
        )
        StatsService service = new StatsService(jdbcTemplate: jdbc)

        when:
        Map snapshot = service.snapshot()

        then:
        LocalDate start = LocalDate.parse(snapshot.daily.first().date as String)
        LocalDate today = start.plusDays(29)
        snapshot.totals == [
            users          : 3L,
            bookmarks      : 5L,
            publicBookmarks: 2L,
            hiddenBookmarks: 1L,
            openReports    : 4L
        ]
        snapshot.daily.size() == 30
        snapshot.daily.first() == [date: start.toString(), bookmarksCreated: 2, activeUsers: 0]
        snapshot.daily[1] == [date: start.plusDays(1).toString(), bookmarksCreated: 0, activeUsers: 3]
        snapshot.daily.last() == [date: today.toString(), bookmarksCreated: 1, activeUsers: 0]
        snapshot.topTags == [[tag: 'grails', count: 4L], [tag: 'gorm', count: 2L]]
        jdbc.dailyArguments.every { it.size() == 1 && it[0] == Timestamp.from(start.atStartOfDay().toInstant(ZoneOffset.UTC)) }
    }

    private static class FakeJdbcTemplate extends JdbcTemplate {
        Map<Integer, Integer> bookmarkCountsByOffset = [:]
        Map<Integer, Integer> userCountsByOffset = [:]
        List<ResultSet> topTagRows = []
        List<List> dailyArguments = []

        @Override
        <T> T queryForObject(String sql, Class<T> requiredType) {
            valueFor(sql) as T
        }

        @Override
        <T> T queryForObject(String sql, Class<T> requiredType, Object... args) {
            valueFor(sql) as T
        }

        private static Object valueFor(String sql) {
            Object value
            if (sql.contains('from user_accounts')) {
                value = 3L
            } else if (sql.contains("visibility = 'public'")) {
                value = 2L
            } else if (sql.contains("status = 'hidden'")) {
                value = 1L
            } else if (sql.contains("status = 'open'")) {
                value = 4L
            } else {
                value = 5L
            }
            value
        }

        @Override
        List<Map<String, Object>> queryForList(String sql, Object... args) {
            dailyArguments << args.toList()
            LocalDate start = (args[0] as Timestamp).toInstant().atOffset(ZoneOffset.UTC).toLocalDate()
            Map<Integer, Integer> counts = sql.contains('from bookmarks') ? bookmarkCountsByOffset : userCountsByOffset
            counts.collect { Integer offset, Integer count ->
                [day: java.sql.Date.valueOf(start.plusDays(offset)), count: count]
            }
        }

        @Override
        <T> List<T> query(String sql, RowMapper<T> rowMapper) {
            mapTopTags(rowMapper)
        }

        @Override
        <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... args) {
            mapTopTags(rowMapper)
        }

        private <T> List<T> mapTopTags(RowMapper<T> rowMapper) {
            topTagRows.withIndex().collect { ResultSet row, int index -> rowMapper.mapRow(row, index) } as List<T>
        }
    }
}
