package dev.stackverse.backend.stats

import dev.stackverse.backend.account.UserAccountRepository
import dev.stackverse.backend.bookmark.BookmarkRepository
import dev.stackverse.backend.bookmark.BookmarkStatus
import dev.stackverse.backend.bookmark.TagCountResponse
import dev.stackverse.backend.bookmark.Visibility
import dev.stackverse.backend.moderation.ReportRepository
import dev.stackverse.backend.moderation.ReportStatus
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.time.ZoneOffset

data class StatsTotals(
    val users: Long,
    val bookmarks: Long,
    val publicBookmarks: Long,
    val hiddenBookmarks: Long,
    val openReports: Long,
)

data class DailyStat(val date: LocalDate, val bookmarksCreated: Long, val activeUsers: Long)

data class AdminStatsResponse(val totals: StatsTotals, val daily: List<DailyStat>, val topTags: List<TagCountResponse>)

private const val DAYS = 30
private const val TOP_TAGS = 10

@Service
@Transactional(readOnly = true)
class StatsService(
    private val bookmarkRepository: BookmarkRepository,
    private val userAccountRepository: UserAccountRepository,
    private val reportRepository: ReportRepository,
    private val jdbcClient: JdbcClient,
) {

    fun stats(): AdminStatsResponse {
        val today = LocalDate.now(ZoneOffset.UTC)
        val from = today.minusDays((DAYS - 1).toLong())
        val bookmarksCreated = countPerDay("bookmarks", "created_at", from)
        val activeUsers = countPerDay("user_accounts", "last_seen", from)
        return AdminStatsResponse(
            totals = StatsTotals(
                users = userAccountRepository.count(),
                bookmarks = bookmarkRepository.count(),
                publicBookmarks = bookmarkRepository.countByVisibility(Visibility.PUBLIC),
                hiddenBookmarks = bookmarkRepository.countByStatus(BookmarkStatus.HIDDEN),
                openReports = reportRepository.countByStatus(ReportStatus.OPEN),
            ),
            // SPEC rule 19: last 30 days including today, oldest first, zero-filled
            daily = (0 until DAYS).map { offset ->
                val date = from.plusDays(offset.toLong())
                DailyStat(date, bookmarksCreated[date] ?: 0, activeUsers[date] ?: 0)
            },
            topTags = bookmarkRepository.topTags(TOP_TAGS).map { TagCountResponse(it.tag, it.count) },
        )
    }

    private fun countPerDay(table: String, column: String, from: LocalDate): Map<LocalDate, Long> =
        jdbcClient
            .sql("select ($column at time zone 'UTC')::date as day, count(*) as cnt from $table where $column >= :from group by day")
            .param("from", from.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime())
            .query { rs, _ -> rs.getObject("day", LocalDate::class.java) to rs.getLong("cnt") }
            .list()
            .toMap()
}

/** ETag / `If-None-Match` handling comes from the ShallowEtagHeaderFilter, as for message reads. */
@RestController
@PreAuthorize("hasRole('moderator')")
class AdminStatsController(private val service: StatsService) {

    @GetMapping("/api/v1/admin/stats")
    fun stats(): ResponseEntity<AdminStatsResponse> =
        ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(service.stats())
}
