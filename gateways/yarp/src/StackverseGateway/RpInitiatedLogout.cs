using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.OpenIdConnect;
using Microsoft.Extensions.Options;

namespace StackverseGateway;

/// <summary>
/// Ends the user's SSO session at the IdP. The gateway contract requires
/// POST /auth/logout to answer 204 rather than bounce the browser through the IdP,
/// so RP-initiated logout happens server-to-server: Keycloak's end_session endpoint
/// accepts a confidential-client POST with the refresh token and tears down the SSO
/// session without any redirect.
/// </summary>
public sealed class RpInitiatedLogout(
    IOptionsMonitor<OpenIdConnectOptions> oidcOptions,
    ILogger<RpInitiatedLogout> logger)
{
    public async Task LogoutAsync(AuthenticationProperties properties, CancellationToken cancellationToken)
    {
        var refreshToken = properties.GetTokenValue("refresh_token");
        if (refreshToken is null)
        {
            return;
        }

        // Best effort throughout: an unreachable or unhappy IdP must never keep the
        // local session alive — the caller destroys it regardless, and Keycloak's
        // SSO session still times out on its own.
        try
        {
            var options = oidcOptions.Get(OpenIdConnectDefaults.AuthenticationScheme);
            var metadata = await options.ConfigurationManager!.GetConfigurationAsync(cancellationToken);

            using var response = await options.Backchannel.PostAsync(
                metadata.EndSessionEndpoint,
                new FormUrlEncodedContent(new Dictionary<string, string>
                {
                    ["client_id"] = options.ClientId!,
                    ["client_secret"] = options.ClientSecret!,
                    ["refresh_token"] = refreshToken,
                }),
                cancellationToken);

            if (!response.IsSuccessStatusCode)
            {
                logger.LogWarning(
                    "IdP logout returned {StatusCode}; local session destroyed anyway",
                    (int)response.StatusCode);
            }
        }
        catch (Exception exception) when (exception is not OperationCanceledException)
        {
            logger.LogWarning(exception, "IdP logout failed; local session destroyed anyway");
        }
    }
}
