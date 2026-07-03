using Microsoft.EntityFrameworkCore;
using StackverseBackend.Bookmarks;
using StackverseBackend.Data;
using StackverseBackend.Moderation;

namespace StackverseBackend.Stats;

public sealed record StatsTotals(long Users, long Bookmarks, long PublicBookmarks, long HiddenBookmarks, long OpenReports);

public sealed record DailyStat(DateOnly Date, long BookmarksCreated, long ActiveUsers);

public sealed record AdminStatsResponse(StatsTotals Totals, IReadOnlyList<DailyStat> Daily, IReadOnlyList<TagCountResponse> TopTags);

/// <summary>ETag / `If-None-Match` handling comes from the EtagMiddleware, as for message reads.</summary>
public static class AdminStatsEndpoints
{
    private const int Days = 30;
    private const int TopTags = 10;

    public static void Map(IEndpointRouteBuilder app)
    {
        app.MapGet("/api/v1/admin/stats", async (AppDbContext db, BookmarkService bookmarks, HttpResponse response) =>
        {
            var today = DateOnly.FromDateTime(DateTime.UtcNow);
            var from = today.AddDays(-(Days - 1));
            var bookmarksCreated = await CountPerDayAsync(db, "bookmarks", "created_at", from);
            var activeUsers = await CountPerDayAsync(db, "user_accounts", "last_seen", from);
            var stats = new AdminStatsResponse(
                new StatsTotals(
                    Users: await db.UserAccounts.LongCountAsync(),
                    Bookmarks: await db.Bookmarks.LongCountAsync(),
                    PublicBookmarks: await db.Bookmarks.LongCountAsync(b => b.Visibility == Visibility.Public),
                    HiddenBookmarks: await db.Bookmarks.LongCountAsync(b => b.Status == BookmarkStatus.Hidden),
                    OpenReports: await db.Reports.LongCountAsync(r => r.Status == ReportStatus.Open)),
                // SPEC rule 19: last 30 days including today, oldest first, zero-filled
                Daily: Enumerable.Range(0, Days)
                    .Select(offset =>
                    {
                        var date = from.AddDays(offset);
                        return new DailyStat(
                            date,
                            bookmarksCreated.GetValueOrDefault(date),
                            activeUsers.GetValueOrDefault(date));
                    })
                    .ToList(),
                TopTags: await bookmarks.TopTagsAsync(TopTags));
            response.Headers.CacheControl = "no-cache";
            return stats;
        }).RequireAuthorization("moderator");
    }

    private static async Task<Dictionary<DateOnly, long>> CountPerDayAsync(
        AppDbContext db, string table, string column, DateOnly from)
    {
        var fromInstant = from.ToDateTime(TimeOnly.MinValue, DateTimeKind.Utc);
        // table and column names are compile-time constants above, never user input;
        // the quoted aliases match DayCountRow's property names, which the outer
        // SELECT that EF composes around raw SQL references case-sensitively
#pragma warning disable EF1002
        var rows = await db.Database.SqlQueryRaw<DayCountRow>(
            $"select ({column} at time zone 'UTC')::date as \"Day\", count(*) as \"Cnt\" from {table} where {column} >= {{0}} group by 1",
            fromInstant).ToListAsync();
#pragma warning restore EF1002
        return rows.ToDictionary(row => row.Day, row => row.Cnt);
    }

    private sealed record DayCountRow(DateOnly Day, long Cnt);
}
