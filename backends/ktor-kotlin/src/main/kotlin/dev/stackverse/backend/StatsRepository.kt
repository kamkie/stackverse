package dev.stackverse.backend

import java.sql.Connection
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.Date

class StatsRepository(private val db: Database) {
    suspend fun stats(): AdminStatsResponse = db.read {
        val today = LocalDate.now(ZoneOffset.UTC)
        val from = today.minusDays(29)
        val bookmarksByDay = countByDay("bookmarks", "created_at", from)
        val usersByDay = countByDay("user_accounts", "last_seen", from)
        AdminStatsResponse(
            totals = StatsTotals(
                users = queryLong("select count(*) from user_accounts"),
                bookmarks = queryLong("select count(*) from bookmarks"),
                publicBookmarks = queryLong("select count(*) from bookmarks where visibility = 'PUBLIC'"),
                hiddenBookmarks = queryLong("select count(*) from bookmarks where status = 'HIDDEN'"),
                openReports = queryLong("select count(*) from reports where status = 'OPEN'"),
            ),
            daily = (0 until 30).map { offset ->
                val date = from.plusDays(offset.toLong())
                DailyStat(date, bookmarksByDay[date] ?: 0, usersByDay[date] ?: 0)
            },
            topTags = query(
                """
                select tag, count(*) as count
                from bookmark_tags
                group by tag
                order by count(*) desc, tag
                limit 10
                """.trimIndent(),
            ) { TagCountResponse(it.getString("tag"), it.getLong("count")) },
        )
    }

    private fun Connection.countByDay(table: String, column: String, from: LocalDate): Map<LocalDate, Long> =
        query(
            """
            select ($column at time zone 'UTC')::date as day, count(*) as count
            from $table
            where $column >= ?
            group by day
            """.trimIndent(),
            listOf(from.atStartOfDay(ZoneOffset.UTC).toInstant()),
        ) { it.getObject("day", LocalDate::class.java) to it.getLong("count") }.toMap()
}
