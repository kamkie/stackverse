using Microsoft.EntityFrameworkCore;
using StackverseBackend.Data;

namespace StackverseBackend.Web;

/// <summary>Liveness/readiness for the container runtime; not proxied by the gateway.</summary>
public static class MetaEndpoints
{
    public static void Map(IEndpointRouteBuilder app)
    {
        app.MapGet("/healthz", () => new { status = "up" }).AllowAnonymous();

        app.MapGet("/readyz", async (AppDbContext db) =>
        {
            try
            {
                await db.Database.ExecuteSqlAsync($"select 1");
                return Results.Ok(new { status = "ready" });
            }
            catch (Exception)
            {
                // the EF interceptors have already emitted dependency_call_failed with duration
                return Results.Json(new { status = "unavailable" }, statusCode: StatusCodes.Status503ServiceUnavailable);
            }
        }).AllowAnonymous();
    }
}
