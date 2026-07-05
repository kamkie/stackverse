package dev.stackverse.backend.stats

import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.stereotype.Service

import java.time.LocalDate
import java.time.ZoneOffset
import java.sql.Timestamp

@Service
class StatsService {
    @Autowired JdbcTemplate jdbcTemplate

    Map snapshot() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC)
        LocalDate start = today.minusDays(29)
        [
            totals : totals(),
            daily  : daily(start, today),
            topTags: topTags()
        ]
    }

    private Map totals() {
        [
            users          : count("select count(*) from user_accounts"),
            bookmarks      : count("select count(*) from bookmarks"),
            publicBookmarks: count("select count(*) from bookmarks where visibility = 'public'"),
            hiddenBookmarks: count("select count(*) from bookmarks where status = 'hidden'"),
            openReports    : count("select count(*) from reports where status = 'open'")
        ]
    }

    private List<Map> daily(LocalDate start, LocalDate today) {
        Map<String, Integer> bookmarks = jdbcTemplate.queryForList("""
            select (created_at at time zone 'utc')::date as day, count(*) as count
            from bookmarks
            where created_at >= ?
            group by day
        """, Timestamp.from(start.atStartOfDay().toInstant(ZoneOffset.UTC))).collectEntries {
            [it.day.toString(), (it.count as Number).intValue()]
        }
        Map<String, Integer> users = jdbcTemplate.queryForList("""
            select (last_seen at time zone 'utc')::date as day, count(*) as count
            from user_accounts
            where last_seen >= ?
            group by day
        """, Timestamp.from(start.atStartOfDay().toInstant(ZoneOffset.UTC))).collectEntries {
            [it.day.toString(), (it.count as Number).intValue()]
        }
        (0..29).collect { offset ->
            LocalDate day = start.plusDays(offset)
            String key = day.toString()
            [date: key, bookmarksCreated: bookmarks[key] ?: 0, activeUsers: users[key] ?: 0]
        }
    }

    private List<Map> topTags() {
        jdbcTemplate.query("""
            select t.tag, count(*) as count
            from bookmark_tags t
            join bookmarks b on b.id = t.bookmark_id
            group by t.tag
            order by count(*) desc, t.tag asc
            limit 10
        """, { rs, rowNum -> [tag: rs.getString("tag"), count: rs.getLong("count")] })
    }

    private long count(String sql) {
        jdbcTemplate.queryForObject(sql, Long)
    }
}
