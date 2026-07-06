package dev.stackverse.backend.stats;

import dev.stackverse.backend.account.UserAccountRepository;
import dev.stackverse.backend.bookmark.BookmarkRepository;
import dev.stackverse.backend.bookmark.BookmarkStatus;
import dev.stackverse.backend.bookmark.TagCountResponse;
import dev.stackverse.backend.bookmark.Visibility;
import dev.stackverse.backend.moderation.ReportRepository;
import dev.stackverse.backend.moderation.ReportStatus;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional(readOnly = true)
public class StatsService {
    private static final int DAYS = 30;
    private static final int TOP_TAGS = 10;

    private final BookmarkRepository bookmarkRepository;
    private final UserAccountRepository userAccountRepository;
    private final ReportRepository reportRepository;
    private final JdbcClient jdbcClient;

    public StatsService(
        BookmarkRepository bookmarkRepository,
        UserAccountRepository userAccountRepository,
        ReportRepository reportRepository,
        JdbcClient jdbcClient
    ) {
        this.bookmarkRepository = bookmarkRepository;
        this.userAccountRepository = userAccountRepository;
        this.reportRepository = reportRepository;
        this.jdbcClient = jdbcClient;
    }

    public AdminStatsResponse stats() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = today.minusDays(DAYS - 1L);
        Map<LocalDate, Long> bookmarksCreated = countPerDay("bookmarks", "created_at", from);
        Map<LocalDate, Long> activeUsers = countPerDay("user_accounts", "last_seen", from);
        return new AdminStatsResponse(
            new StatsTotals(
                userAccountRepository.count(),
                bookmarkRepository.count(),
                bookmarkRepository.countByVisibility(Visibility.PUBLIC),
                bookmarkRepository.countByStatus(BookmarkStatus.HIDDEN),
                reportRepository.countByStatus(ReportStatus.OPEN)
            ),
            IntStream.range(0, DAYS)
                .mapToObj(offset -> {
                    LocalDate date = from.plusDays(offset);
                    return new DailyStat(date, bookmarksCreated.getOrDefault(date, 0L), activeUsers.getOrDefault(date, 0L));
                })
                .toList(),
            bookmarkRepository.topTags(TOP_TAGS).stream()
                .map(row -> new TagCountResponse(row.getTag(), row.getCount()))
                .toList()
        );
    }

    private Map<LocalDate, Long> countPerDay(String table, String column, LocalDate from) {
        return jdbcClient
            .sql("select (" + column + " at time zone 'UTC')::date as day, count(*) as cnt from " + table + " where " + column + " >= :from group by day")
            .param("from", from.atStartOfDay(ZoneOffset.UTC).toOffsetDateTime())
            .query((rs, rowNum) -> Map.entry(rs.getObject("day", LocalDate.class), rs.getLong("cnt")))
            .list()
            .stream()
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    }
}
