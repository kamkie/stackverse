using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using StackverseBackend.Accounts;
using StackverseBackend.Bookmarks;

namespace StackverseBackend.Tests;

public class AccountAuthorizationIntegrationTests
{
    [Fact]
    public async Task Protected_endpoint_without_authentication_returns_problem_401()
    {
        await using var factory = new BackendFactory();
        using var client = factory.CreateClient();

        var response = await client.GetAsync("/api/v1/me");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
    }

    [Fact]
    public async Task Presented_bearer_header_without_an_authenticated_principal_is_401_even_on_a_public_endpoint()
    {
        await using var factory = new BackendFactory();
        using var client = factory.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, "/healthz");
        // The test scheme returns NoResult here; this isolates the post-authentication
        // middleware boundary without claiming to exercise live JWT/JWKS validation.
        request.Headers.Authorization = new AuthenticationHeaderValue("Bearer", "not-a-token");

        var response = await client.SendAsync(request);

        var problem = await AssertProblemAsync(response, HttpStatusCode.Unauthorized, "Unauthorized");
        Assert.Equal("Missing or invalid bearer token.", problem.GetProperty("detail").GetString());
    }

    [Fact]
    public async Task Authenticated_request_preserves_first_seen_and_advances_last_seen()
    {
        await using var factory = new BackendFactory();
        var firstSeen = TestData.At(-120);
        var lastSeen = TestData.At(-60);
        await factory.SeedAsync(db =>
        {
            db.UserAccounts.Add(TestData.User("returning", firstSeen: firstSeen, lastSeen: lastSeen));
            return Task.CompletedTask;
        });

        using var client = factory.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, "/api/v1/me");
        request.AuthenticateAs("returning");

        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var account = await factory.ReadAsync(db => db.UserAccounts.AsNoTracking().SingleAsync(u => u.Username == "returning"));
        Assert.Equal(firstSeen, account.FirstSeen);
        Assert.True(account.LastSeen > lastSeen);
    }

    [Fact]
    public async Task Blocked_user_is_rejected_but_anonymous_public_feed_remains_available()
    {
        await using var factory = new BackendFactory();
        var publicBookmark = TestData.Bookmark(
            owner: "owner",
            visibility: Visibility.Public,
            status: BookmarkStatus.Active);
        await factory.SeedAsync(db =>
        {
            db.UserAccounts.Add(TestData.User(
                "blocked",
                UserAccountStatus.Blocked,
                blockedReason: "policy"));
            db.Bookmarks.Add(publicBookmark);
            db.Messages.AddRange(
                TestData.Message("error.account.blocked", "en", "Account blocked"),
                TestData.Message("error.account.blocked", "pl", "Konto zablokowane"));
            return Task.CompletedTask;
        });

        using var client = factory.CreateClient();
        using var blockedRequest = new HttpRequestMessage(HttpMethod.Get, "/api/v1/me");
        blockedRequest.AuthenticateAs("blocked");
        blockedRequest.Headers.AcceptLanguage.ParseAdd("pl");

        var blockedResponse = await client.SendAsync(blockedRequest);
        var publicResponse = await client.GetAsync("/api/v1/bookmarks?visibility=public");

        var problem = await AssertProblemAsync(blockedResponse, HttpStatusCode.Forbidden, "Forbidden");
        Assert.Equal("Konto zablokowane", problem.GetProperty("detail").GetString());
        Assert.Equal(HttpStatusCode.OK, publicResponse.StatusCode);
        var publicPage = await ReadJsonAsync(publicResponse);
        Assert.Equal(publicBookmark.Id, Assert.Single(publicPage.GetProperty("items").EnumerateArray()).GetProperty("id").GetGuid());
    }

    [Fact]
    public async Task Regular_user_gets_problem_403_on_an_admin_endpoint()
    {
        await using var factory = new BackendFactory();
        using var client = factory.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, "/api/v1/admin/users");
        request.AuthenticateAs("regular");

        var response = await client.SendAsync(request);

        var problem = await AssertProblemAsync(response, HttpStatusCode.Forbidden, "Forbidden");
        Assert.Equal("You do not have the role required for this operation.", problem.GetProperty("detail").GetString());
    }

    [Fact]
    public async Task Admin_blocks_and_unblocks_a_user_with_atomic_audit_entries()
    {
        await using var factory = new BackendFactory();
        await factory.SeedAsync(db =>
        {
            db.UserAccounts.Add(TestData.User("target"));
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();

        var blocked = await SendStatusAsync(client, "admin", "target", new { status = "blocked", reason = "  policy  " });
        var blockedBody = await ReadJsonAsync(blocked);
        var active = await SendStatusAsync(client, "admin", "target", new { status = "active", reason = "ignored" });

        Assert.Equal(HttpStatusCode.OK, blocked.StatusCode);
        Assert.Equal("blocked", blockedBody.GetProperty("status").GetString());
        Assert.Equal("policy", blockedBody.GetProperty("blockedReason").GetString());
        Assert.Equal(HttpStatusCode.OK, active.StatusCode);

        var state = await factory.ReadAsync(async db => new
        {
            Account = await db.UserAccounts.AsNoTracking().SingleAsync(u => u.Username == "target"),
            Audits = await db.AuditEntries.AsNoTracking()
                .Where(a => a.TargetId == "target")
                .OrderBy(a => a.CreatedAt)
                .ToListAsync(),
        });
        Assert.Equal(UserAccountStatus.Active, state.Account.Status);
        Assert.Null(state.Account.BlockedReason);
        Assert.Equal(["user.blocked", "user.unblocked"], state.Audits.Select(a => a.Action));
        Assert.Contains("policy", state.Audits[0].Detail);
        Assert.Null(state.Audits[1].Detail);
    }

    [Fact]
    public async Task Invalid_block_requests_do_not_mutate_or_audit()
    {
        await using var factory = new BackendFactory();
        await factory.SeedAsync(db =>
        {
            db.UserAccounts.Add(TestData.User("target"));
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();

        var selfBlock = await SendStatusAsync(client, "admin", "admin", new { status = "blocked", reason = "policy" });
        var missingReason = await SendStatusAsync(client, "admin", "target", new { status = "blocked" });

        await AssertProblemAsync(selfBlock, HttpStatusCode.Conflict, "Conflict");
        var validation = await AssertProblemAsync(missingReason, HttpStatusCode.BadRequest, "Bad Request");
        Assert.Equal("validation.block.reason.required", validation.GetProperty("errors")[0].GetProperty("messageKey").GetString());
        var state = await factory.ReadAsync(async db => new
        {
            Target = await db.UserAccounts.AsNoTracking().SingleAsync(u => u.Username == "target"),
            AuditCount = await db.AuditEntries.CountAsync(),
        });
        Assert.Equal(UserAccountStatus.Active, state.Target.Status);
        Assert.Equal(0, state.AuditCount);
    }

    [Fact]
    public async Task Admin_list_and_get_include_status_filter_and_bookmark_count()
    {
        await using var factory = new BackendFactory();
        await factory.SeedAsync(db =>
        {
            db.UserAccounts.AddRange(
                TestData.User("blocked", UserAccountStatus.Blocked, blockedReason: "policy"),
                TestData.User("active"));
            db.Bookmarks.AddRange(
                TestData.Bookmark(owner: "blocked"),
                TestData.Bookmark(owner: "blocked"));
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();
        using var listRequest = new HttpRequestMessage(HttpMethod.Get, "/api/v1/admin/users?status=blocked&size=10");
        listRequest.AuthenticateAs("admin", "admin");

        var listResponse = await client.SendAsync(listRequest);
        using var getRequest = new HttpRequestMessage(HttpMethod.Get, "/api/v1/admin/users/blocked");
        getRequest.AuthenticateAs("admin", "admin");
        var getResponse = await client.SendAsync(getRequest);

        Assert.Equal(HttpStatusCode.OK, listResponse.StatusCode);
        var list = await ReadJsonAsync(listResponse);
        var listed = Assert.Single(list.GetProperty("items").EnumerateArray());
        Assert.Equal("blocked", listed.GetProperty("username").GetString());
        Assert.Equal(2, listed.GetProperty("bookmarkCount").GetInt64());
        Assert.Equal(HttpStatusCode.OK, getResponse.StatusCode);
        var fetched = await ReadJsonAsync(getResponse);
        Assert.Equal("policy", fetched.GetProperty("blockedReason").GetString());
        Assert.Equal(2, fetched.GetProperty("bookmarkCount").GetInt64());
    }

    [Fact]
    public async Task Me_returns_only_application_roles_in_stable_order()
    {
        await using var factory = new BackendFactory();
        using var client = factory.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, "/api/v1/me");
        request.AuthenticateAs("admin", "moderator", "offline_access", "admin");

        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var body = await ReadJsonAsync(response);
        Assert.Equal("admin", body.GetProperty("username").GetString());
        Assert.Equal(["admin", "moderator"], body.GetProperty("roles").EnumerateArray().Select(role => role.GetString()));
    }

    private static async Task<HttpResponseMessage> SendStatusAsync(
        HttpClient client,
        string actor,
        string target,
        object body)
    {
        var request = new HttpRequestMessage(HttpMethod.Put, $"/api/v1/admin/users/{target}/status")
        {
            Content = JsonContent.Create(body),
        };
        request.AuthenticateAs(actor, "admin");
        return await client.SendAsync(request);
    }

    private static async Task<JsonElement> AssertProblemAsync(
        HttpResponseMessage response,
        HttpStatusCode status,
        string title)
    {
        Assert.Equal(status, response.StatusCode);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        var problem = await ReadJsonAsync(response);
        Assert.Equal(title, problem.GetProperty("title").GetString());
        Assert.Equal((int)status, problem.GetProperty("status").GetInt32());
        return problem;
    }

    private static async Task<JsonElement> ReadJsonAsync(HttpResponseMessage response) =>
        JsonDocument.Parse(await response.Content.ReadAsStringAsync()).RootElement.Clone();
}
