using System.Security.Claims;
using StackverseBackend.Bookmarks;
using StackverseBackend.Common;

namespace StackverseBackend.Moderation;

public static class ModerationEndpoints
{
    public static void Map(IEndpointRouteBuilder app)
    {
        app.MapPost("/api/v1/bookmarks/{id:guid}/reports", async (
            ModerationService service, ClaimsPrincipal user, Guid id, ReportRequest request) =>
        {
            var report = await service.ReportAsync(user.Identity!.Name!, id, request);
            return Results.Json(ReportResponse.Of(report), statusCode: StatusCodes.Status201Created);
        });

        app.MapGet("/api/v1/reports", async (
            ModerationService service, ClaimsPrincipal user, string? status, int page = 0, int size = 20) =>
        {
            Paging.RequireValidPaging(page, size);
            var (items, total) = await service.ListMyReportsAsync(
                user.Identity!.Name!, Wire.ParseQuery<ReportStatus>(status, "status"), page, size);
            return PageResponse.Create(items.Select(ReportResponse.Of).ToList(), page, size, total);
        });

        app.MapPut("/api/v1/reports/{id:guid}", async (
            ModerationService service, ClaimsPrincipal user, Guid id, ReportRequest request) =>
            ReportResponse.Of(await service.UpdateMyReportAsync(user.Identity!.Name!, id, request)));

        app.MapDelete("/api/v1/reports/{id:guid}", async (ModerationService service, ClaimsPrincipal user, Guid id) =>
        {
            await service.WithdrawAsync(user.Identity!.Name!, id);
            return Results.NoContent();
        });

        app.MapGet("/api/v1/admin/reports", async (
            ModerationService service, string? status, int page = 0, int size = 20) =>
        {
            Paging.RequireValidPaging(page, size);
            var wanted = Wire.ParseQuery<ReportStatus>(status, "status") ?? ReportStatus.Open;
            var (items, total) = await service.ListReportsAsync(wanted, page, size);
            return PageResponse.Create(items.Select(ReportResponse.Of).ToList(), page, size, total);
        }).RequireAuthorization("moderator");

        app.MapPut("/api/v1/admin/reports/{id:guid}", async (
            ModerationService service, ClaimsPrincipal user, Guid id, ReportResolutionRequest request) =>
            ReportResponse.Of(await service.ResolveAsync(user.Identity!.Name!, id, request)))
            .RequireAuthorization("moderator");

        app.MapPut("/api/v1/admin/bookmarks/{id:guid}/status", async (
            ModerationService service, ClaimsPrincipal user, Guid id, BookmarkStatusRequest request) =>
            BookmarkResponse.Of(await service.SetBookmarkStatusAsync(user.Identity!.Name!, id, request)))
            .RequireAuthorization("moderator");
    }
}
