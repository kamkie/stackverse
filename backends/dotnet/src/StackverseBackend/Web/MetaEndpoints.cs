using Microsoft.EntityFrameworkCore;
using StackverseBackend.Data;

namespace StackverseBackend.Web;

/// <summary>Liveness/readiness for the container runtime; not proxied by the gateway.</summary>
public static class MetaEndpoints
{
    public static void Map(IEndpointRouteBuilder app)
    {
        app.MapGet("/healthz", () => new { status = "up" }).AllowAnonymous();

        app.MapGet("/readyz", async (AppDbContext db, ILoggerFactory loggerFactory) =>
        {
            try
            {
                await db.Database.ExecuteSqlAsync($"select 1");
                return Results.Ok(new { status = "ready" });
            }
            catch (Exception exception)
            {
                loggerFactory.CreateLogger("StackverseBackend.Readiness")
                    .Event(LogLevel.Error, "dependency_call_failed", "failure",
                        "Readiness probe could not reach PostgreSQL", exception,
                        ("dependency", "postgresql"),
                        ("error_code", exception.GetType().Name));
                return Results.Json(new { status = "unavailable" }, statusCode: StatusCodes.Status503ServiceUnavailable);
            }
        }).AllowAnonymous();
    }
}
