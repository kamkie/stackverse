using System.Security.Claims;
using StackverseBackend.Common;

namespace StackverseBackend.Bookmarks;

/// <summary>
/// `GET /api/v1/bookmarks` is the deprecated predecessor of the v2 listing; its
/// `Deprecation` / `Sunset` / `Link` headers are added by
/// <see cref="DeprecationHeadersMiddleware"/> so every response carries them.
/// </summary>
public static class BookmarkEndpoints
{
    public static void Map(IEndpointRouteBuilder app)
    {
        var v1 = app.MapGroup("/api/v1/bookmarks");

        v1.MapGet("", async (
            BookmarkService service,
            ClaimsPrincipal user,
            string[] tag,
            string? q,
            string? visibility,
            int page = 0,
            int size = 20) =>
        {
            Paging.RequireValidPaging(page, size);
            Paging.RequireMaxLength(q, 200, "q");
            var query = new BookmarkListQuery(
                BookmarkService.ValidateQueryTags(tag),
                q,
                Wire.ParseQuery<Visibility>(visibility, "visibility"));
            var (items, total) = await service.ListOffsetAsync(Caller(user), query, page, size);
            return PageResponse<BookmarkResponse>.Of(items.Select(BookmarkResponse.Of).ToList(), page, size, total);
        }).AllowAnonymous();

        v1.MapPost("", async (BookmarkService service, ClaimsPrincipal user, BookmarkRequest request) =>
        {
            var bookmark = await service.CreateAsync(user.Identity!.Name!, request);
            return Results.Created($"/api/v1/bookmarks/{bookmark.Id}", BookmarkResponse.Of(bookmark));
        });

        v1.MapGet("/{id:guid}", async (BookmarkService service, ClaimsPrincipal user, Guid id) =>
            BookmarkResponse.Of(await service.GetAsync(Caller(user), id))).AllowAnonymous();

        v1.MapPut("/{id:guid}", async (BookmarkService service, ClaimsPrincipal user, Guid id, BookmarkRequest request) =>
            BookmarkResponse.Of(await service.UpdateAsync(user.Identity!.Name!, id, request)));

        v1.MapDelete("/{id:guid}", async (BookmarkService service, ClaimsPrincipal user, Guid id) =>
        {
            await service.DeleteAsync(user.Identity!.Name!, id);
            return Results.NoContent();
        });

        app.MapGet("/api/v2/bookmarks", async (
            BookmarkService service,
            ClaimsPrincipal user,
            string[] tag,
            string? q,
            string? visibility,
            string? cursor,
            int size = 20) =>
        {
            Paging.RequireValidPaging(page: 0, size);
            Paging.RequireMaxLength(q, 200, "q");
            var query = new BookmarkListQuery(
                BookmarkService.ValidateQueryTags(tag),
                q,
                Wire.ParseQuery<Visibility>(visibility, "visibility"));
            var slice = await service.ListKeysetAsync(
                Caller(user), query, cursor is null ? null : BookmarkCursor.Decode(cursor), size);
            return new BookmarkCursorPageResponse(
                slice.Items.Select(BookmarkResponse.Of).ToList(),
                slice.NextCursor?.Encode());
        }).AllowAnonymous();

        app.MapGet("/api/v1/tags", async (BookmarkService service, ClaimsPrincipal user) =>
            new TagListResponse(await service.CountTagsByOwnerAsync(user.Identity!.Name!)));
    }

    private static string? Caller(ClaimsPrincipal user) =>
        user.Identity?.IsAuthenticated == true ? user.Identity.Name : null;
}
