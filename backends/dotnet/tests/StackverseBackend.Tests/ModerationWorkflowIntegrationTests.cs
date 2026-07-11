using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using StackverseBackend.Bookmarks;
using StackverseBackend.Moderation;

namespace StackverseBackend.Tests;

public class ModerationWorkflowIntegrationTests
{
    [Fact]
    public async Task Reporter_can_create_update_list_withdraw_and_create_a_replacement()
    {
        await using var factory = new BackendFactory();
        var bookmark = TestData.Bookmark(owner: "owner", visibility: Visibility.Public);
        await factory.SeedAsync(db =>
        {
            db.Bookmarks.Add(bookmark);
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();

        var created = await SendAsAsync(client, HttpMethod.Post, $"/api/v1/bookmarks/{bookmark.Id}/reports", "reporter", new
        {
            reason = "spam",
            comment = "first",
        });
        Assert.Equal(HttpStatusCode.Created, created.StatusCode);
        var reportId = (await ReadJsonAsync(created)).GetProperty("id").GetGuid();

        var duplicate = await SendAsAsync(client, HttpMethod.Post, $"/api/v1/bookmarks/{bookmark.Id}/reports", "reporter", new
        {
            reason = "other",
        });
        Assert.Equal(HttpStatusCode.Conflict, duplicate.StatusCode);

        var updated = await SendAsAsync(client, HttpMethod.Put, $"/api/v1/reports/{reportId}", "reporter", new
        {
            reason = "broken-link",
            comment = "updated",
        });
        Assert.Equal(HttpStatusCode.OK, updated.StatusCode);
        var updatedBody = await ReadJsonAsync(updated);
        Assert.Equal("broken-link", updatedBody.GetProperty("reason").GetString());
        Assert.Equal("updated", updatedBody.GetProperty("comment").GetString());

        var listed = await SendAsAsync(client, HttpMethod.Get, "/api/v1/reports?status=open", "reporter");
        Assert.Equal(HttpStatusCode.OK, listed.StatusCode);
        var item = Assert.Single((await ReadJsonAsync(listed)).GetProperty("items").EnumerateArray());
        Assert.Equal(reportId, item.GetProperty("id").GetGuid());

        var withdrawn = await SendAsAsync(client, HttpMethod.Delete, $"/api/v1/reports/{reportId}", "reporter");
        Assert.Equal(HttpStatusCode.NoContent, withdrawn.StatusCode);

        var replacement = await SendAsAsync(client, HttpMethod.Post, $"/api/v1/bookmarks/{bookmark.Id}/reports", "reporter", new
        {
            reason = "offensive",
        });
        Assert.Equal(HttpStatusCode.Created, replacement.StatusCode);
        Assert.NotEqual(reportId, (await ReadJsonAsync(replacement)).GetProperty("id").GetGuid());

        var state = await factory.ReadAsync(async db => new
        {
            Reports = await db.Reports.AsNoTracking().ToListAsync(),
            AuditCount = await db.AuditEntries.CountAsync(),
        });
        Assert.Single(state.Reports);
        Assert.Equal(ReportReason.Offensive, state.Reports[0].Reason);
        Assert.Equal(0, state.AuditCount);
    }

    [Theory]
    [InlineData(Visibility.Private, BookmarkStatus.Active)]
    [InlineData(Visibility.Public, BookmarkStatus.Hidden)]
    public async Task Reporting_private_or_hidden_bookmarks_is_masked_as_404(
        Visibility visibility,
        BookmarkStatus status)
    {
        await using var factory = new BackendFactory();
        var bookmark = TestData.Bookmark(owner: "owner", visibility: visibility, status: status);
        await factory.SeedAsync(db =>
        {
            db.Bookmarks.Add(bookmark);
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();

        var response = await SendAsAsync(client, HttpMethod.Post, $"/api/v1/bookmarks/{bookmark.Id}/reports", "reporter", new
        {
            reason = "spam",
        });

        Assert.Equal(HttpStatusCode.NotFound, response.StatusCode);
        Assert.False(await factory.ReadAsync(db => db.Reports.AnyAsync()));
    }

    [Fact]
    public async Task Reporter_cannot_mutate_another_users_or_a_resolved_report()
    {
        await using var factory = new BackendFactory();
        var bookmark = TestData.Bookmark(owner: "owner", visibility: Visibility.Public);
        var open = TestData.Report(bookmark.Id, reporter: "alice");
        var dismissed = TestData.Report(bookmark.Id, reporter: "alice", status: ReportStatus.Dismissed);
        await factory.SeedAsync(db =>
        {
            db.Bookmarks.Add(bookmark);
            db.Reports.AddRange(open, dismissed);
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();

        var otherUpdate = await SendAsAsync(client, HttpMethod.Put, $"/api/v1/reports/{open.Id}", "bob", new { reason = "other" });
        var otherDelete = await SendAsAsync(client, HttpMethod.Delete, $"/api/v1/reports/{open.Id}", "bob");
        var resolvedUpdate = await SendAsAsync(client, HttpMethod.Put, $"/api/v1/reports/{dismissed.Id}", "alice", new { reason = "other" });
        var resolvedDelete = await SendAsAsync(client, HttpMethod.Delete, $"/api/v1/reports/{dismissed.Id}", "alice");

        Assert.Equal(HttpStatusCode.NotFound, otherUpdate.StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, otherDelete.StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, resolvedUpdate.StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, resolvedDelete.StatusCode);
        Assert.Equal(2, await factory.ReadAsync(db => db.Reports.CountAsync()));
    }

    [Fact]
    public async Task Moderator_can_dismiss_then_reopen_clearing_resolution_fields()
    {
        await using var factory = new BackendFactory();
        var bookmark = TestData.Bookmark(owner: "owner", visibility: Visibility.Public);
        var report = TestData.Report(bookmark.Id, reporter: "reporter");
        await factory.SeedAsync(db =>
        {
            db.Bookmarks.Add(bookmark);
            db.Reports.Add(report);
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();

        var dismissed = await ResolveAsync(client, report.Id, "dismissed", "confirmed");
        Assert.Equal(HttpStatusCode.OK, dismissed.StatusCode);
        var dismissedBody = await ReadJsonAsync(dismissed);
        Assert.Equal("dismissed", dismissedBody.GetProperty("status").GetString());
        Assert.Equal("moderator", dismissedBody.GetProperty("resolvedBy").GetString());
        Assert.Equal("confirmed", dismissedBody.GetProperty("resolutionNote").GetString());

        var reopened = await ResolveAsync(client, report.Id, "open", "must be ignored");
        Assert.Equal(HttpStatusCode.OK, reopened.StatusCode);
        var reopenedBody = await ReadJsonAsync(reopened);
        Assert.Equal("open", reopenedBody.GetProperty("status").GetString());
        Assert.False(reopenedBody.TryGetProperty("resolvedBy", out _));
        Assert.False(reopenedBody.TryGetProperty("resolvedAt", out _));
        Assert.False(reopenedBody.TryGetProperty("resolutionNote", out _));

        var state = await factory.ReadAsync(async db => new
        {
            Report = await db.Reports.AsNoTracking().SingleAsync(item => item.Id == report.Id),
            Bookmark = await db.Bookmarks.AsNoTracking().SingleAsync(item => item.Id == bookmark.Id),
            Actions = await db.AuditEntries.AsNoTracking().Select(entry => entry.Action).ToListAsync(),
        });
        Assert.Equal(ReportStatus.Open, state.Report.Status);
        Assert.Null(state.Report.ResolvedBy);
        Assert.Null(state.Report.ResolvedAt);
        Assert.Null(state.Report.ResolutionNote);
        Assert.Equal(BookmarkStatus.Active, state.Bookmark.Status);
        Assert.Contains("report.resolved", state.Actions);
        Assert.Contains("report.reopened", state.Actions);
    }

    [Fact]
    public async Task Reopening_conflicts_when_the_reporter_has_another_open_report()
    {
        await using var factory = new BackendFactory();
        var bookmark = TestData.Bookmark(owner: "owner", visibility: Visibility.Public);
        var dismissed = TestData.Report(bookmark.Id, reporter: "reporter", status: ReportStatus.Dismissed);
        var open = TestData.Report(bookmark.Id, reporter: "reporter");
        await factory.SeedAsync(db =>
        {
            db.Bookmarks.Add(bookmark);
            db.Reports.AddRange(dismissed, open);
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();

        var response = await ResolveAsync(client, dismissed.Id, "open", null);

        Assert.Equal(HttpStatusCode.Conflict, response.StatusCode);
        var state = await factory.ReadAsync(db => db.Reports.AsNoTracking().SingleAsync(item => item.Id == dismissed.Id));
        Assert.Equal(ReportStatus.Dismissed, state.Status);
    }

    [Fact]
    public async Task Moderator_hide_and_restore_preserve_visibility_and_write_audits()
    {
        await using var factory = new BackendFactory();
        var bookmark = TestData.Bookmark(owner: "owner", visibility: Visibility.Public);
        await factory.SeedAsync(db =>
        {
            db.Bookmarks.Add(bookmark);
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();

        var hidden = await SetBookmarkStatusAsync(client, bookmark.Id, "hidden", "policy");
        var restored = await SetBookmarkStatusAsync(client, bookmark.Id, "active", "restored");

        Assert.Equal(HttpStatusCode.OK, hidden.StatusCode);
        Assert.Equal("hidden", (await ReadJsonAsync(hidden)).GetProperty("status").GetString());
        Assert.Equal(HttpStatusCode.OK, restored.StatusCode);
        var restoredBody = await ReadJsonAsync(restored);
        Assert.Equal("active", restoredBody.GetProperty("status").GetString());
        Assert.Equal("public", restoredBody.GetProperty("visibility").GetString());

        var state = await factory.ReadAsync(async db => new
        {
            Bookmark = await db.Bookmarks.AsNoTracking().SingleAsync(item => item.Id == bookmark.Id),
            Audits = await db.AuditEntries.AsNoTracking()
                .Where(entry => entry.Action == "bookmark.status-changed")
                .ToListAsync(),
        });
        Assert.Equal(BookmarkStatus.Active, state.Bookmark.Status);
        Assert.Equal(Visibility.Public, state.Bookmark.Visibility);
        Assert.Equal(2, state.Audits.Count);
        Assert.Contains(state.Audits, entry => entry.Detail!.Contains("hidden", StringComparison.Ordinal));
        Assert.Contains(state.Audits, entry => entry.Detail!.Contains("active", StringComparison.Ordinal));
    }

    [Fact]
    public async Task Moderator_queue_defaults_to_open_and_orders_oldest_first()
    {
        await using var factory = new BackendFactory();
        var bookmark = TestData.Bookmark(owner: "owner", visibility: Visibility.Public);
        var oldest = TestData.Report(bookmark.Id, reporter: "a", createdAt: TestData.At(-3));
        var newest = TestData.Report(bookmark.Id, reporter: "b", createdAt: TestData.At(-1));
        await factory.SeedAsync(db =>
        {
            db.Bookmarks.Add(bookmark);
            db.Reports.AddRange(
                newest,
                TestData.Report(bookmark.Id, reporter: "resolved", status: ReportStatus.Dismissed, createdAt: TestData.At(-4)),
                oldest);
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();

        var response = await SendAsAsync(client, HttpMethod.Get, "/api/v1/admin/reports?size=10", "moderator", roles: ["moderator"]);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var items = (await ReadJsonAsync(response)).GetProperty("items").EnumerateArray()
            .Select(item => item.GetProperty("id").GetGuid())
            .ToList();
        Assert.Equal([oldest.Id, newest.Id], items);
    }

    [Fact]
    public async Task Report_validation_collects_reason_and_comment_errors_without_persisting()
    {
        await using var factory = new BackendFactory();
        var bookmark = TestData.Bookmark(owner: "owner", visibility: Visibility.Public);
        await factory.SeedAsync(db =>
        {
            db.Bookmarks.Add(bookmark);
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();

        var response = await SendAsAsync(client, HttpMethod.Post, $"/api/v1/bookmarks/{bookmark.Id}/reports", "reporter", new
        {
            reason = "unknown",
            comment = new string('x', 1001),
        });

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var keys = (await ReadJsonAsync(response)).GetProperty("errors").EnumerateArray()
            .Select(error => error.GetProperty("messageKey").GetString())
            .ToList();
        Assert.Equal(["validation.report.reason.invalid", "validation.report.comment.too-long"], keys);
        Assert.False(await factory.ReadAsync(db => db.Reports.AnyAsync()));
    }

    [Fact]
    public async Task Resolution_and_bookmark_status_validation_do_not_mutate_or_audit()
    {
        await using var factory = new BackendFactory();
        var bookmark = TestData.Bookmark(owner: "owner", visibility: Visibility.Public);
        var report = TestData.Report(bookmark.Id);
        await factory.SeedAsync(db =>
        {
            db.Bookmarks.Add(bookmark);
            db.Reports.Add(report);
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();

        var resolution = await SendAsAsync(
            client,
            HttpMethod.Put,
            $"/api/v1/admin/reports/{report.Id}",
            "moderator",
            new { resolution = "unknown", note = new string('x', 1001) },
            ["moderator"]);
        var status = await SendAsAsync(
            client,
            HttpMethod.Put,
            $"/api/v1/admin/bookmarks/{bookmark.Id}/status",
            "moderator",
            new { note = new string('x', 1001) },
            ["moderator"]);

        Assert.Equal(HttpStatusCode.BadRequest, resolution.StatusCode);
        Assert.Equal(HttpStatusCode.BadRequest, status.StatusCode);
        var resolutionKeys = (await ReadJsonAsync(resolution)).GetProperty("errors").EnumerateArray()
            .Select(error => error.GetProperty("messageKey").GetString())
            .ToList();
        Assert.Equal(["validation.resolution.invalid", "validation.resolution.note.too-long"], resolutionKeys);
        var statusKeys = (await ReadJsonAsync(status)).GetProperty("errors").EnumerateArray()
            .Select(error => error.GetProperty("messageKey").GetString())
            .ToList();
        Assert.Equal(["validation.bookmark-status.invalid", "validation.bookmark-status.note.too-long"], statusKeys);
        var state = await factory.ReadAsync(async db => new
        {
            Report = await db.Reports.AsNoTracking().SingleAsync(item => item.Id == report.Id),
            Bookmark = await db.Bookmarks.AsNoTracking().SingleAsync(item => item.Id == bookmark.Id),
            AuditCount = await db.AuditEntries.CountAsync(),
        });
        Assert.Equal(ReportStatus.Open, state.Report.Status);
        Assert.Equal(BookmarkStatus.Active, state.Bookmark.Status);
        Assert.Equal(0, state.AuditCount);
    }

    private static Task<HttpResponseMessage> ResolveAsync(
        HttpClient client,
        Guid reportId,
        string resolution,
        string? note) =>
        SendAsAsync(
            client,
            HttpMethod.Put,
            $"/api/v1/admin/reports/{reportId}",
            "moderator",
            new { resolution, note },
            ["moderator"]);

    private static Task<HttpResponseMessage> SetBookmarkStatusAsync(
        HttpClient client,
        Guid bookmarkId,
        string status,
        string? note) =>
        SendAsAsync(
            client,
            HttpMethod.Put,
            $"/api/v1/admin/bookmarks/{bookmarkId}/status",
            "moderator",
            new { status, note },
            ["moderator"]);

    private static async Task<HttpResponseMessage> SendAsAsync(
        HttpClient client,
        HttpMethod method,
        string path,
        string actor,
        object? body = null,
        params string[] roles)
    {
        using var request = new HttpRequestMessage(method, path);
        if (body is not null)
        {
            request.Content = JsonContent.Create(body);
        }
        request.AuthenticateAs(actor, roles);
        return await client.SendAsync(request);
    }

    private static async Task<JsonElement> ReadJsonAsync(HttpResponseMessage response) =>
        JsonDocument.Parse(await response.Content.ReadAsStringAsync()).RootElement.Clone();
}
