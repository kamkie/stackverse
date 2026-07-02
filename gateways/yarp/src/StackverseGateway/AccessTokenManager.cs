using System.Globalization;
using System.Text.Json;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Authentication.OpenIdConnect;
using Microsoft.Extensions.Options;

namespace StackverseGateway;

/// <summary>
/// Hands out the session's access token for the /api relay, refreshing it against the
/// IdP token endpoint when it is about to expire and persisting the refreshed tokens
/// back into the Redis session.
///
/// This is a deliberate hand-rolled refresh path rather than a dependency on
/// Duende.AccessTokenManagement: the whole exchange is one form POST, and this repo
/// optimizes for self-contained code a reader can follow end to end. Concurrent
/// requests may occasionally refresh twice; Keycloak permits refresh-token reuse by
/// default, so the loser of the race simply stores an equally valid token.
/// </summary>
public sealed class AccessTokenManager(
    IOptionsMonitor<OpenIdConnectOptions> oidcOptions,
    RedisTicketStore ticketStore,
    ILogger<AccessTokenManager> logger)
{
    /// <summary>Refresh slightly early so a token cannot expire mid-flight to the backend.</summary>
    private static readonly TimeSpan ExpirySkew = TimeSpan.FromSeconds(30);

    /// <summary>
    /// Returns a valid access token for the authenticated session, or null when the
    /// session can no longer produce one (refresh token expired or revoked).
    /// </summary>
    public async Task<string?> GetAccessTokenAsync(AuthenticateResult auth, CancellationToken cancellationToken)
    {
        var properties = auth.Properties!;
        var accessToken = properties.GetTokenValue("access_token");
        if (accessToken is null)
        {
            return null;
        }

        var expiresAt = properties.GetTokenValue("expires_at");
        if (expiresAt is not null
            && DateTimeOffset.TryParse(expiresAt, CultureInfo.InvariantCulture, DateTimeStyles.RoundtripKind, out var expiry)
            && expiry - ExpirySkew > DateTimeOffset.UtcNow)
        {
            return accessToken;
        }

        return await RefreshAsync(auth, cancellationToken);
    }

    private async Task<string?> RefreshAsync(AuthenticateResult auth, CancellationToken cancellationToken)
    {
        var properties = auth.Properties!;
        var refreshToken = properties.GetTokenValue("refresh_token");
        if (refreshToken is null)
        {
            return null;
        }

        var options = oidcOptions.Get(OpenIdConnectDefaults.AuthenticationScheme);
        var metadata = await options.ConfigurationManager!.GetConfigurationAsync(cancellationToken);

        using var response = await options.Backchannel.PostAsync(
            metadata.TokenEndpoint,
            new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["grant_type"] = "refresh_token",
                ["refresh_token"] = refreshToken,
                ["client_id"] = options.ClientId!,
                ["client_secret"] = options.ClientSecret!,
            }),
            cancellationToken);

        if (!response.IsSuccessStatusCode)
        {
            // degraded but self-healing (the caller destroys the session) — WARN per docs/LOGGING.md §5
            logger.Event(LogLevel.Warning, "token_refresh_failed", "failure",
                $"Token refresh rejected by the IdP ({(int)response.StatusCode}); treating the session as expired",
                fields: [("error_code", "idp_rejected"), ("idp_status", (int)response.StatusCode)]);
            return null;
        }

        using var payload = JsonDocument.Parse(await response.Content.ReadAsStringAsync(cancellationToken));
        var root = payload.RootElement;
        var newAccessToken = root.GetProperty("access_token").GetString()!;
        var newRefreshToken = root.TryGetProperty("refresh_token", out var rotated) ? rotated.GetString()! : refreshToken;
        var expiresIn = root.TryGetProperty("expires_in", out var lifetime) ? lifetime.GetInt32() : 300;

        properties.UpdateTokenValue("access_token", newAccessToken);
        properties.UpdateTokenValue("refresh_token", newRefreshToken);
        properties.UpdateTokenValue(
            "expires_at",
            DateTimeOffset.UtcNow.AddSeconds(expiresIn).ToString("o", CultureInfo.InvariantCulture));

        // Persist the rotated tokens so every gateway instance sees them.
        if (properties.Items.TryGetValue(RedisTicketStore.SessionKeyItem, out var sessionKey) && sessionKey is not null)
        {
            await ticketStore.RenewAsync(
                sessionKey,
                new AuthenticationTicket(auth.Principal!, properties, CookieAuthenticationDefaults.AuthenticationScheme));
        }

        return newAccessToken;
    }
}
