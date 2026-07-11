using System.Net;
using System.Net.Http.Headers;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;

namespace StackverseBackend.Tests;

public class MessageWorkflowIntegrationTests
{
    [Fact]
    public async Task Admin_message_crud_changes_etags_and_audits_every_mutation()
    {
        await using var factory = new BackendFactory();
        using var client = factory.CreateClient();

        var created = await SendAdminAsync(client, HttpMethod.Post, "/api/v1/messages", new
        {
            key = "ui.test.label",
            language = "en",
            text = "Original",
            description = "A test label",
        });

        Assert.Equal(HttpStatusCode.Created, created.StatusCode);
        var createdBody = await ReadJsonAsync(created);
        var id = createdBody.GetProperty("id").GetGuid();
        Assert.Equal($"/api/v1/messages/{id}", created.Headers.Location?.ToString());

        var firstRead = await client.GetAsync($"/api/v1/messages/{id}");
        Assert.Equal(HttpStatusCode.OK, firstRead.StatusCode);
        var firstEtag = firstRead.Headers.ETag;
        Assert.NotNull(firstEtag);

        var updated = await SendAdminAsync(client, HttpMethod.Put, $"/api/v1/messages/{id}", new
        {
            key = "ui.test.label",
            language = "en",
            text = "Updated",
            description = "Updated context",
        });
        Assert.Equal(HttpStatusCode.OK, updated.StatusCode);

        var secondRead = await client.GetAsync($"/api/v1/messages/{id}");
        Assert.Equal(HttpStatusCode.OK, secondRead.StatusCode);
        Assert.NotEqual(firstEtag, secondRead.Headers.ETag);
        Assert.Equal("Updated", (await ReadJsonAsync(secondRead)).GetProperty("text").GetString());

        var deleted = await SendAdminAsync(client, HttpMethod.Delete, $"/api/v1/messages/{id}");
        Assert.Equal(HttpStatusCode.NoContent, deleted.StatusCode);
        Assert.Equal(HttpStatusCode.NotFound, (await client.GetAsync($"/api/v1/messages/{id}")).StatusCode);

        var audits = await factory.ReadAsync(db => db.AuditEntries.AsNoTracking()
            .Where(entry => entry.TargetId == id.ToString())
            .ToListAsync());
        Assert.Equal(3, audits.Count);
        Assert.Equal(
            ["message.created", "message.deleted", "message.updated"],
            audits.Select(entry => entry.Action).Order(StringComparer.Ordinal));
        Assert.All(audits, entry => Assert.Contains("ui.test.label", entry.Detail!));
    }

    [Fact]
    public async Task Duplicate_create_and_update_return_409_without_extra_audits()
    {
        await using var factory = new BackendFactory();
        var first = TestData.Message("ui.first", "en", "First");
        var second = TestData.Message("ui.second", "en", "Second");
        await factory.SeedAsync(db =>
        {
            db.Messages.AddRange(first, second);
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();

        var duplicateCreate = await SendAdminAsync(client, HttpMethod.Post, "/api/v1/messages", new
        {
            key = "ui.first",
            language = "en",
            text = "Duplicate",
        });
        var duplicateUpdate = await SendAdminAsync(client, HttpMethod.Put, $"/api/v1/messages/{second.Id}", new
        {
            key = "ui.first",
            language = "en",
            text = "Duplicate",
        });

        Assert.Equal(HttpStatusCode.Conflict, duplicateCreate.StatusCode);
        Assert.Equal(HttpStatusCode.Conflict, duplicateUpdate.StatusCode);
        var state = await factory.ReadAsync(async db => new
        {
            Messages = await db.Messages.AsNoTracking().OrderBy(message => message.Key).ToListAsync(),
            AuditCount = await db.AuditEntries.CountAsync(),
        });
        Assert.Equal(["ui.first", "ui.second"], state.Messages.Select(message => message.Key));
        Assert.Equal("Second", state.Messages[1].Text);
        Assert.Equal(0, state.AuditCount);
    }

    [Fact]
    public async Task Bundle_uses_requested_language_falls_back_per_key_and_revalidates()
    {
        await using var factory = new BackendFactory();
        await factory.SeedAsync(db =>
        {
            db.Messages.AddRange(
                TestData.Message("ui.a", "en", "English A"),
                TestData.Message("ui.b", "en", "English B"),
                TestData.Message("ui.a", "pl", "Polish A"));
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();

        var response = await client.GetAsync("/api/v1/messages/bundle?lang=pl");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("pl", response.Content.Headers.ContentLanguage.Single());
        Assert.Equal("no-cache", response.Headers.CacheControl?.ToString());
        Assert.NotNull(response.Headers.ETag);
        var body = await ReadJsonAsync(response);
        Assert.Equal("pl", body.GetProperty("language").GetString());
        Assert.Equal("Polish A", body.GetProperty("messages").GetProperty("ui.a").GetString());
        Assert.Equal("English B", body.GetProperty("messages").GetProperty("ui.b").GetString());

        using var revalidation = new HttpRequestMessage(HttpMethod.Get, "/api/v1/messages/bundle?lang=pl");
        revalidation.Headers.IfNoneMatch.Add(response.Headers.ETag!);
        var notModified = await client.SendAsync(revalidation);
        Assert.Equal(HttpStatusCode.NotModified, notModified.StatusCode);
        Assert.Empty(await notModified.Content.ReadAsByteArrayAsync());
    }

    [Fact]
    public async Task Public_message_reads_support_exact_filters_and_resource_lookup()
    {
        await using var factory = new BackendFactory();
        var wanted = TestData.Message("ui.wanted", "en", "Wanted");
        await factory.SeedAsync(db =>
        {
            db.Messages.AddRange(
                wanted,
                TestData.Message("ui.wanted", "pl", "Szukane"),
                TestData.Message("ui.other", "en", "Other"));
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();

        var listResponse = await client.GetAsync("/api/v1/messages?key=ui.wanted&language=en&size=10");
        var getResponse = await client.GetAsync($"/api/v1/messages/{wanted.Id}");
        var missingResponse = await client.GetAsync($"/api/v1/messages/{Guid.NewGuid()}");

        Assert.Equal(HttpStatusCode.OK, listResponse.StatusCode);
        var page = await ReadJsonAsync(listResponse);
        var item = Assert.Single(page.GetProperty("items").EnumerateArray());
        Assert.Equal(wanted.Id, item.GetProperty("id").GetGuid());
        Assert.Equal(1, page.GetProperty("totalItems").GetInt64());
        Assert.Equal(HttpStatusCode.OK, getResponse.StatusCode);
        Assert.Equal("no-cache", getResponse.Headers.CacheControl?.ToString());
        Assert.Equal("Wanted", (await ReadJsonAsync(getResponse)).GetProperty("text").GetString());
        Assert.Equal(HttpStatusCode.NotFound, missingResponse.StatusCode);
    }

    private static async Task<HttpResponseMessage> SendAdminAsync(
        HttpClient client,
        HttpMethod method,
        string path,
        object? body = null)
    {
        using var request = new HttpRequestMessage(method, path);
        if (body is not null)
        {
            request.Content = JsonContent.Create(body);
        }
        request.AuthenticateAs("admin", "admin", "moderator");
        return await client.SendAsync(request);
    }

    private static async Task<JsonElement> ReadJsonAsync(HttpResponseMessage response) =>
        JsonDocument.Parse(await response.Content.ReadAsStringAsync()).RootElement.Clone();
}
