using Microsoft.AspNetCore.Authentication;
using StackverseBackend.Common;
using StackverseBackend.Messages;

namespace StackverseBackend.Accounts;

/// <summary>
/// Runs right after JWT authentication: upserts the caller's account row (SPEC rule 16)
/// and rejects blocked accounts with a localized 403 problem document (rule 17).
/// Also the single place that turns a presented-but-rejected bearer token into a 401 —
/// on every endpoint, public surface included, matching the reference backend.
/// </summary>
public sealed class UserAccountMiddleware(RequestDelegate next, ILogger<UserAccountMiddleware> logger)
{
    public async Task InvokeAsync(HttpContext context, UserAccountService accountService, MessageLocalizer localizer)
    {
        if (context.User.Identity?.IsAuthenticated == true)
        {
            var account = await accountService.RecordSeenAsync(context.User.Identity.Name!);
            if (account.Status == UserAccountStatus.Blocked)
            {
                logger.Event(LogLevel.Warning, "blocked_user_rejected", "denied",
                    "Refused a request from a blocked account",
                    fields: [("actor", context.User.Identity.Name)]);
                await Problems.Write(context, StatusCodes.Status403Forbidden, "Forbidden",
                    await localizer.LocalizeAsync("error.account.blocked", context.Request));
                return;
            }
        }
        else if (HasBearerToken(context.Request))
        {
            // fires only when a bearer token was presented and rejected — an expected
            // 401 and a security signal, never above INFO (docs/LOGGING.md §3)
            var failure = context.Features.Get<IAuthenticateResultFeature>()?.AuthenticateResult?.Failure;
            logger.Event(LogLevel.Information, "jwt_validation_failed", "failure", "Rejected a bearer token",
                fields: [("error_code", failure?.GetType().Name ?? "invalid_token")]);
            await Problems.Write(context, StatusCodes.Status401Unauthorized, "Unauthorized",
                "Missing or invalid bearer token.");
            return;
        }
        await next(context);
    }

    private static bool HasBearerToken(HttpRequest request) =>
        request.Headers.Authorization.FirstOrDefault()?.StartsWith("Bearer ", StringComparison.OrdinalIgnoreCase) == true;
}
