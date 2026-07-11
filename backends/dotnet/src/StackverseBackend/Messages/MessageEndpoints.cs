using System.Security.Claims;
using Microsoft.EntityFrameworkCore;
using StackverseBackend.Common;
using StackverseBackend.Data;

namespace StackverseBackend.Messages;

/// <summary>
/// Message reads are public and revalidatable: the ETag / `If-None-Match` / `304`
/// handling is done by <see cref="EtagMiddleware"/>; endpoints only add
/// `Cache-Control: no-cache` (SPEC rule 10).
/// </summary>
public static class MessageEndpoints
{
    public static void Map(IEndpointRouteBuilder app)
    {
        var group = app.MapGroup("/api/v1/messages");

        group.MapGet("", async (
            AppDbContext db,
            HttpResponse response,
            string? key,
            string? q,
            string? language,
            int page = 0,
            int size = 20) =>
        {
            Paging.RequireValidPaging(page, size);
            Paging.RequireMaxLength(q, 200, "q");
            var filtered = db.Messages.AsNoTracking().AsQueryable();
            if (key is not null)
            {
                filtered = filtered.Where(m => m.Key == key);
            }
            if (language is not null)
            {
                filtered = filtered.Where(m => m.Language == language);
            }
            if (!string.IsNullOrWhiteSpace(q))
            {
                var pattern = $"%{Paging.EscapeLike(q)}%";
                filtered = filtered.Where(m =>
                    EF.Functions.ILike(m.Key, pattern, @"\") || EF.Functions.ILike(m.Text, pattern, @"\"));
            }
            var total = await filtered.LongCountAsync();
            var items = await filtered.OrderBy(m => m.Key).ThenBy(m => m.Language)
                .Skip(Paging.SkipOf(page, size)).Take(size).ToListAsync();
            response.Headers.CacheControl = "no-cache";
            return PageResponse.Create(items.Select(MessageResponse.Of).ToList(), page, size, total);
        }).AllowAnonymous();

        group.MapGet("/bundle", async (
            MessageService service,
            LanguageResolver languageResolver,
            HttpRequest request,
            HttpResponse response,
            string? lang) =>
        {
            var language = await languageResolver.ResolveAsync(lang, request.Headers.AcceptLanguage.FirstOrDefault());
            response.Headers.CacheControl = "no-cache";
            response.Headers.ContentLanguage = language;
            return new MessageBundleResponse(language, await service.BundleAsync(language));
        }).AllowAnonymous();

        group.MapGet("/{id:guid}", async (AppDbContext db, HttpResponse response, Guid id) =>
        {
            var message = await db.Messages.AsNoTracking().SingleOrDefaultAsync(m => m.Id == id)
                ?? throw new NotFoundProblem();
            response.Headers.CacheControl = "no-cache";
            return MessageResponse.Of(message);
        }).AllowAnonymous();

        group.MapPost("", async (MessageService service, ClaimsPrincipal user, MessageRequest request) =>
        {
            var message = await service.CreateAsync(user.Identity!.Name!, request);
            return Results.Created($"/api/v1/messages/{message.Id}", MessageResponse.Of(message));
        }).RequireAuthorization("admin");

        group.MapPut("/{id:guid}", async (MessageService service, ClaimsPrincipal user, Guid id, MessageRequest request) =>
            MessageResponse.Of(await service.UpdateAsync(user.Identity!.Name!, id, request)))
            .RequireAuthorization("admin");

        group.MapDelete("/{id:guid}", async (MessageService service, ClaimsPrincipal user, Guid id) =>
        {
            await service.DeleteAsync(user.Identity!.Name!, id);
            return Results.NoContent();
        }).RequireAuthorization("admin");
    }
}
