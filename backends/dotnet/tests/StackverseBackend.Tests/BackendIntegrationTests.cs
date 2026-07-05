using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using StackverseBackend.Bookmarks;
using StackverseBackend.Moderation;

namespace StackverseBackend.Tests;

public class BackendIntegrationTests
{
    [Fact]
    public async Task Actioned_report_hides_bookmark_and_auto_resolves_open_siblings()
    {
        await using var factory = new BackendFactory();
        var bookmarkId = Guid.NewGuid();
        var reportId = Guid.NewGuid();
        var siblingId = Guid.NewGuid();
        await factory.SeedAsync(db =>
        {
            db.Bookmarks.Add(Bookmark(bookmarkId, "owner", Visibility.Public, BookmarkStatus.Active));
            db.Reports.AddRange(
                Report(reportId, bookmarkId, "demo", ReportReason.Spam),
                Report(siblingId, bookmarkId, "other", ReportReason.Offensive));
            return Task.CompletedTask;
        });

        using var client = factory.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Put, $"/api/v1/admin/reports/{reportId}")
        {
            Content = JsonContent.Create(new { resolution = "actioned", note = "confirmed" }),
        };
        request.AuthenticateAs("moderator", "moderator");

        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = await JsonDocument.ParseAsync(await response.Content.ReadAsStreamAsync());
        Assert.Equal("actioned", body.RootElement.GetProperty("status").GetString());
        Assert.Equal("moderator", body.RootElement.GetProperty("resolvedBy").GetString());

        var state = await factory.ReadAsync(async db => new
        {
            BookmarkStatus = (await db.Bookmarks.SingleAsync(b => b.Id == bookmarkId)).Status,
            Reports = await db.Reports.AsNoTracking().OrderBy(r => r.Id).ToListAsync(),
            AuditActions = await db.AuditEntries.AsNoTracking().Select(a => a.Action).ToListAsync(),
        });

        Assert.Equal(BookmarkStatus.Hidden, state.BookmarkStatus);
        Assert.All(state.Reports, report =>
        {
            Assert.Equal(ReportStatus.Actioned, report.Status);
            Assert.Equal("moderator", report.ResolvedBy);
            Assert.Equal("confirmed", report.ResolutionNote);
        });
        Assert.Contains("bookmark.status-changed", state.AuditActions);
        Assert.Equal(2, state.AuditActions.Count(action => action == "report.resolved"));
    }

    [Fact]
    public async Task Bookmark_reads_mask_private_and_hidden_bookmarks_but_allow_owner_and_public_reads()
    {
        await using var factory = new BackendFactory();
        var privateId = Guid.NewGuid();
        var publicId = Guid.NewGuid();
        var hiddenId = Guid.NewGuid();
        await factory.SeedAsync(db =>
        {
            db.Bookmarks.AddRange(
                Bookmark(privateId, "owner", Visibility.Private, BookmarkStatus.Active),
                Bookmark(publicId, "owner", Visibility.Public, BookmarkStatus.Active),
                Bookmark(hiddenId, "owner", Visibility.Public, BookmarkStatus.Hidden));
            return Task.CompletedTask;
        });

        using var client = factory.CreateClient();
        using var ownerRequest = new HttpRequestMessage(HttpMethod.Get, $"/api/v1/bookmarks/{privateId}");
        ownerRequest.AuthenticateAs("owner");
        using var otherRequest = new HttpRequestMessage(HttpMethod.Get, $"/api/v1/bookmarks/{privateId}");
        otherRequest.AuthenticateAs("other");

        Assert.Equal(HttpStatusCode.OK, (await client.SendAsync(ownerRequest)).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await client.SendAsync(otherRequest)).StatusCode);
        Assert.Equal(HttpStatusCode.OK, (await client.GetAsync($"/api/v1/bookmarks/{publicId}")).StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await client.GetAsync($"/api/v1/bookmarks/{hiddenId}")).StatusCode);
    }

    [Fact]
    public async Task Owner_cannot_republish_hidden_bookmark()
    {
        await using var factory = new BackendFactory();
        var bookmarkId = Guid.NewGuid();
        await factory.SeedAsync(db =>
        {
            db.Bookmarks.Add(Bookmark(bookmarkId, "owner", Visibility.Private, BookmarkStatus.Hidden));
            return Task.CompletedTask;
        });

        using var client = factory.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Put, $"/api/v1/bookmarks/{bookmarkId}")
        {
            Content = JsonContent.Create(new
            {
                url = "https://example.com/updated",
                title = "Updated",
                tags = Array.Empty<string>(),
                visibility = "public",
            }),
        };
        request.AuthenticateAs("owner");

        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
        var body = await JsonDocument.ParseAsync(await response.Content.ReadAsStreamAsync());
        Assert.Equal("Conflict", body.RootElement.GetProperty("title").GetString());
        Assert.Equal("error.bookmark.hidden-publish", body.RootElement.GetProperty("detail").GetString());

        var bookmark = await factory.ReadAsync(db => db.Bookmarks.AsNoTracking().SingleAsync(b => b.Id == bookmarkId));
        Assert.Equal(Visibility.Private, bookmark.Visibility);
        Assert.Equal(BookmarkStatus.Hidden, bookmark.Status);
    }

    [Fact]
    public async Task Keyset_public_feed_returns_stable_cursor_slices_without_overlap()
    {
        await using var factory = new BackendFactory();
        var oldest = Guid.Parse("00000000-0000-0000-0000-000000000001");
        var middle = Guid.Parse("00000000-0000-0000-0000-000000000002");
        var newest = Guid.Parse("00000000-0000-0000-0000-000000000003");
        await factory.SeedAsync(db =>
        {
            db.Bookmarks.AddRange(
                Bookmark(oldest, "owner", Visibility.Public, BookmarkStatus.Active, Clock(-3)),
                Bookmark(middle, "owner", Visibility.Public, BookmarkStatus.Active, Clock(-2)),
                Bookmark(newest, "owner", Visibility.Public, BookmarkStatus.Active, Clock(-1)));
            return Task.CompletedTask;
        });

        using var client = factory.CreateClient();
        var first = await GetJsonAsync(client, "/api/v2/bookmarks?visibility=public&size=2");
        var firstItems = first.GetProperty("items").EnumerateArray().Select(item => item.GetProperty("id").GetGuid()).ToList();
        var cursor = first.GetProperty("nextCursor").GetString();

        var second = await GetJsonAsync(
            client,
            $"/api/v2/bookmarks?visibility=public&size=2&cursor={Uri.EscapeDataString(cursor!)}");
        var secondItems = second.GetProperty("items").EnumerateArray().Select(item => item.GetProperty("id").GetGuid()).ToList();

        Assert.Equal([newest, middle], firstItems);
        Assert.Equal([oldest], secondItems);
        Assert.False(second.TryGetProperty("nextCursor", out _));
    }

    private static async Task<JsonElement> GetJsonAsync(HttpClient client, string path)
    {
        var response = await client.GetAsync(path);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return JsonDocument.Parse(await response.Content.ReadAsStringAsync()).RootElement;
    }

    private static Bookmark Bookmark(
        Guid id,
        string owner,
        Visibility visibility,
        BookmarkStatus status,
        DateTime? createdAt = null)
    {
        var now = createdAt ?? Clock(-1);
        return new Bookmark
        {
            Id = id,
            Owner = owner,
            Url = $"https://example.com/{id}",
            Title = $"Bookmark {id}",
            Tags = [],
            Visibility = visibility,
            Status = status,
            CreatedAt = now,
            UpdatedAt = now,
        };
    }

    private static Report Report(Guid id, Guid bookmarkId, string reporter, ReportReason reason) => new()
    {
        Id = id,
        BookmarkId = bookmarkId,
        Reporter = reporter,
        Reason = reason,
        Status = ReportStatus.Open,
        CreatedAt = Clock(-1),
    };

    private static DateTime Clock(int minutes) =>
        new DateTime(2026, 7, 5, 12, 0, 0, DateTimeKind.Utc) + TimeSpan.FromMinutes(minutes);
}
