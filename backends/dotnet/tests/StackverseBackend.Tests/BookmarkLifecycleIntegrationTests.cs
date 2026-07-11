using System.Net;
using System.Net.Http.Json;
using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using StackverseBackend.Bookmarks;

namespace StackverseBackend.Tests;

public class BookmarkLifecycleIntegrationTests
{
    [Fact]
    public async Task Owner_can_create_replace_and_delete_while_nonowner_sees_404()
    {
        await using var factory = new BackendFactory();
        using var client = factory.CreateClient();
        using var createRequest = new HttpRequestMessage(HttpMethod.Post, "/api/v1/bookmarks")
        {
            Content = JsonContent.Create(new
            {
                url = " https://example.com/original ",
                title = " Original ",
                notes = "notes",
                tags = new List<string> { " DotNet ", "dotnet", "testing" },
                visibility = "private",
                ignored = "unknown fields are allowed",
            }),
        };
        createRequest.AuthenticateAs("owner");

        var created = await client.SendAsync(createRequest);

        Assert.Equal(HttpStatusCode.Created, created.StatusCode);
        var createdBody = await ReadJsonAsync(created);
        var id = createdBody.GetProperty("id").GetGuid();
        Assert.Equal($"/api/v1/bookmarks/{id}", created.Headers.Location?.ToString());
        Assert.Equal("https://example.com/original", createdBody.GetProperty("url").GetString());
        Assert.Equal("Original", createdBody.GetProperty("title").GetString());
        Assert.Equal(["dotnet", "testing"], createdBody.GetProperty("tags").EnumerateArray().Select(tag => tag.GetString()));

        using var nonownerUpdate = UpdateRequest(id, "other", "Other update", "public");
        var maskedUpdate = await client.SendAsync(nonownerUpdate);
        Assert.Equal(HttpStatusCode.NotFound, maskedUpdate.StatusCode);

        using var ownerUpdate = UpdateRequest(id, "owner", "Updated", "public");
        var updated = await client.SendAsync(ownerUpdate);
        Assert.Equal(HttpStatusCode.OK, updated.StatusCode);
        var updatedBody = await ReadJsonAsync(updated);
        Assert.Equal("Updated", updatedBody.GetProperty("title").GetString());
        Assert.Equal("public", updatedBody.GetProperty("visibility").GetString());

        using var nonownerDelete = new HttpRequestMessage(HttpMethod.Delete, $"/api/v1/bookmarks/{id}");
        nonownerDelete.AuthenticateAs("other");
        Assert.Equal(HttpStatusCode.NotFound, (await client.SendAsync(nonownerDelete)).StatusCode);

        using var ownerDelete = new HttpRequestMessage(HttpMethod.Delete, $"/api/v1/bookmarks/{id}");
        ownerDelete.AuthenticateAs("owner");
        Assert.Equal(HttpStatusCode.NoContent, (await client.SendAsync(ownerDelete)).StatusCode);
        Assert.False(await factory.ReadAsync(db => db.Bookmarks.AnyAsync(bookmark => bookmark.Id == id)));
    }

    [Fact]
    public async Task Owner_listing_includes_hidden_items_and_repeatable_tags_are_AND()
    {
        await using var factory = new BackendFactory();
        var matching = TestData.Bookmark(
            owner: "owner",
            visibility: Visibility.Private,
            status: BookmarkStatus.Hidden,
            createdAt: TestData.At(-1),
            tags: ["one", "two"]);
        await factory.SeedAsync(db =>
        {
            db.Bookmarks.AddRange(
                matching,
                TestData.Bookmark(owner: "owner", createdAt: TestData.At(-2), tags: ["one"]),
                TestData.Bookmark(owner: "other", visibility: Visibility.Public, tags: ["one", "two"]));
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Get, "/api/v1/bookmarks?tag=one&tag=two&size=10");
        request.AuthenticateAs("owner");

        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.True(response.Headers.Contains("Deprecation"));
        var page = await ReadJsonAsync(response);
        var item = Assert.Single(page.GetProperty("items").EnumerateArray());
        Assert.Equal(matching.Id, item.GetProperty("id").GetGuid());
        Assert.Equal("hidden", item.GetProperty("status").GetString());
        Assert.Equal(1, page.GetProperty("totalItems").GetInt64());
    }

    [Fact]
    public async Task Anonymous_public_listing_excludes_private_and_hidden_bookmarks()
    {
        await using var factory = new BackendFactory();
        var visible = TestData.Bookmark(owner: "other", visibility: Visibility.Public, status: BookmarkStatus.Active);
        await factory.SeedAsync(db =>
        {
            db.Bookmarks.AddRange(
                visible,
                TestData.Bookmark(owner: "other", visibility: Visibility.Public, status: BookmarkStatus.Hidden),
                TestData.Bookmark(owner: "other", visibility: Visibility.Private, status: BookmarkStatus.Active));
            return Task.CompletedTask;
        });
        using var client = factory.CreateClient();

        var response = await client.GetAsync("/api/v1/bookmarks?visibility=public");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var page = await ReadJsonAsync(response);
        var item = Assert.Single(page.GetProperty("items").EnumerateArray());
        Assert.Equal(visible.Id, item.GetProperty("id").GetGuid());
    }

    [Fact]
    public async Task Anonymous_default_listing_is_a_deprecated_problem_401()
    {
        await using var factory = new BackendFactory();
        using var client = factory.CreateClient();

        var response = await client.GetAsync("/api/v1/bookmarks");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        Assert.Equal("@1782864000", response.Headers.GetValues("Deprecation").Single());
        Assert.Equal("Thu, 01 Jul 2027 00:00:00 GMT", response.Headers.GetValues("Sunset").Single());
    }

    [Fact]
    public async Task Invalid_listing_filter_returns_400_instead_of_falling_back()
    {
        await using var factory = new BackendFactory();
        using var client = factory.CreateClient();

        var response = await client.GetAsync("/api/v1/bookmarks?visibility=everyone");

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        var problem = await ReadJsonAsync(response);
        Assert.Equal("unknown visibility: everyone", problem.GetProperty("detail").GetString());
    }

    private static HttpRequestMessage UpdateRequest(Guid id, string actor, string title, string visibility)
    {
        var request = new HttpRequestMessage(HttpMethod.Put, $"/api/v1/bookmarks/{id}")
        {
            Content = JsonContent.Create(new
            {
                url = "https://example.com/updated",
                title,
                tags = Array.Empty<string>(),
                visibility,
            }),
        };
        request.AuthenticateAs(actor);
        return request;
    }

    private static async Task<JsonElement> ReadJsonAsync(HttpResponseMessage response) =>
        JsonDocument.Parse(await response.Content.ReadAsStringAsync()).RootElement.Clone();
}
