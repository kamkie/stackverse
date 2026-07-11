using System.Net;
using System.Text.Json;

namespace StackverseBackend.Tests;

public class AdminAuditIntegrationTests
{
    [Fact]
    public async Task Audit_log_filters_every_contract_dimension_and_deserializes_detail()
    {
        await using var factory = new BackendFactory();
        var matching = TestData.Audit(
            "admin",
            "user.blocked",
            "user",
            "alice",
            TestData.At(-2),
            "{\"reason\":\"policy\"}");
        await factory.SeedAsync(db =>
        {
            db.AuditEntries.AddRange(
                TestData.Audit("admin", "user.blocked", "user", "bob", TestData.At(-4)),
                matching,
                TestData.Audit("moderator", "bookmark.status-changed", "bookmark", "alice", TestData.At(-1)));
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();
        var from = Uri.EscapeDataString(TestData.At(-3).ToString("O"));
        var to = Uri.EscapeDataString(TestData.At(-1).ToString("O"));
        using var request = new HttpRequestMessage(
            HttpMethod.Get,
            $"/api/v1/admin/audit-log?actor=admin&action=user.blocked&targetType=user&targetId=alice&from={from}&to={to}&size=10");
        request.AuthenticateAs("admin", "admin");

        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var page = JsonDocument.Parse(await response.Content.ReadAsStringAsync()).RootElement;
        Assert.Equal(1, page.GetProperty("totalItems").GetInt64());
        var item = Assert.Single(page.GetProperty("items").EnumerateArray());
        Assert.Equal(matching.Id, item.GetProperty("id").GetGuid());
        Assert.Equal("policy", item.GetProperty("detail").GetProperty("reason").GetString());
    }

    [Fact]
    public async Task Audit_log_requires_admin_role()
    {
        await using var factory = new BackendFactory();
        using var client = factory.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, "/api/v1/admin/audit-log");
        request.AuthenticateAs("moderator", "moderator");

        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
    }
}
