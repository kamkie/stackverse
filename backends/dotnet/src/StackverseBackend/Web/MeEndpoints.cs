using System.Security.Claims;

namespace StackverseBackend.Web;

public sealed record UserResponse(string Username, string? Name, string? Email, IReadOnlyList<string> Roles);

public static class MeEndpoints
{
    /// <summary>The two application roles; everything else in `realm_access.roles` is Keycloak plumbing.</summary>
    private static readonly string[] AppRoles = ["moderator", "admin"];

    public static void Map(IEndpointRouteBuilder app)
    {
        app.MapGet("/api/v1/me", (ClaimsPrincipal user) => new UserResponse(
            user.Identity!.Name!,
            user.FindFirstValue("name"),
            user.FindFirstValue("email"),
            AppRoles.Where(user.IsInRole).Order(StringComparer.Ordinal).ToList()));
    }
}
