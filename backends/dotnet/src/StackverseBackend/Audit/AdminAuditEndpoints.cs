using System.Text.Json;
using Microsoft.EntityFrameworkCore;
using StackverseBackend.Common;
using StackverseBackend.Data;

namespace StackverseBackend.Audit;

public sealed record AuditEntryResponse(
    Guid Id,
    string Actor,
    string Action,
    string TargetType,
    string TargetId,
    Dictionary<string, JsonElement>? Detail,
    DateTime CreatedAt);

public static class AdminAuditEndpoints
{
    public static void Map(IEndpointRouteBuilder app)
    {
        app.MapGet("/api/v1/admin/audit-log", async (
            AppDbContext db,
            string? actor,
            string? action,
            string? targetType,
            string? targetId,
            DateTime? from,
            DateTime? to,
            int page = 0,
            int size = 20) =>
        {
            Paging.RequireValidPaging(page, size);
            var filtered = db.AuditEntries.AsNoTracking().AsQueryable();
            if (actor is not null)
            {
                filtered = filtered.Where(a => a.Actor == actor);
            }
            if (action is not null)
            {
                filtered = filtered.Where(a => a.Action == action);
            }
            if (targetType is not null)
            {
                filtered = filtered.Where(a => a.TargetType == targetType);
            }
            if (targetId is not null)
            {
                filtered = filtered.Where(a => a.TargetId == targetId);
            }
            if (from is { } fromInstant)
            {
                filtered = filtered.Where(a => a.CreatedAt >= fromInstant.ToUniversalTime());
            }
            if (to is { } toInstant)
            {
                filtered = filtered.Where(a => a.CreatedAt <= toInstant.ToUniversalTime());
            }
            var total = await filtered.LongCountAsync();
            var items = await filtered.OrderByDescending(a => a.CreatedAt)
                .Skip(Paging.SkipOf(page, size)).Take(size).ToListAsync();
            return PageResponse.Create(
                items.Select(entry => new AuditEntryResponse(
                    entry.Id,
                    entry.Actor,
                    entry.Action,
                    entry.TargetType,
                    entry.TargetId,
                    entry.Detail is null ? null : JsonSerializer.Deserialize<Dictionary<string, JsonElement>>(entry.Detail),
                    entry.CreatedAt)).ToList(),
                page, size, total);
        }).RequireAuthorization("admin");
    }
}
