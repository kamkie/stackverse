using System.Net;
using System.Text.RegularExpressions;

namespace StackverseGateway.Tests;

/// <summary>
/// Drives the real authorization code flow against the Testcontainers Keycloak,
/// shared by every test class that needs a logged-in gateway session.
/// </summary>
public static class AuthFlows
{
    /// <summary>
    /// Follows the challenge to the Keycloak container, submits the login form like a
    /// browser would, then replays the callback against the gateway. Returns the
    /// XSRF-TOKEN issued along the way.
    /// </summary>
    public static async Task<string> LogInAsync(HttpClient client, string username, string password)
    {
        var challenge = await client.GetAsync("/auth/login");
        Assert.Equal(HttpStatusCode.Found, challenge.StatusCode);
        var xsrfToken = ExtractSetCookie(challenge, "XSRF-TOKEN")
            ?? throw new InvalidOperationException("gateway did not issue an XSRF-TOKEN cookie");

        // Talk to Keycloak directly, as the browser would. Keycloak marks its cookies
        // Secure even over http, which .NET's CookieContainer would then refuse to send
        // back over http (browsers treat localhost as a secure context; CookieContainer
        // does not) — so carry the cookies by hand.
        using var keycloakHandler = new HttpClientHandler { AllowAutoRedirect = false, UseCookies = false };
        using var keycloak = new HttpClient(keycloakHandler);

        var loginPage = await keycloak.GetAsync(challenge.Headers.Location);
        Assert.Equal(HttpStatusCode.OK, loginPage.StatusCode);
        var html = await loginPage.Content.ReadAsStringAsync();
        var formAction = WebUtility.HtmlDecode(Regex.Match(html, "action=\"([^\"]+)\"").Groups[1].Value);
        Assert.False(string.IsNullOrEmpty(formAction), "no login form found on the Keycloak page");
        var keycloakCookies = string.Join("; ", loginPage.Headers
            .GetValues("Set-Cookie")
            .Select(c => c[..c.IndexOf(';')]));

        using var credentialsRequest = new HttpRequestMessage(HttpMethod.Post, formAction)
        {
            Content = new FormUrlEncodedContent(new Dictionary<string, string>
            {
                ["username"] = username,
                ["password"] = password,
            }),
        };
        credentialsRequest.Headers.Add("Cookie", keycloakCookies);
        var credentials = await keycloak.SendAsync(credentialsRequest);
        if (credentials.StatusCode != HttpStatusCode.Found)
        {
            var body = await credentials.Content.ReadAsStringAsync();
            Assert.Fail($"Keycloak login POST returned {(int)credentials.StatusCode}: {body[..Math.Min(body.Length, 500)]}");
        }
        var callbackUrl = credentials.Headers.Location!;
        Assert.StartsWith("http://localhost:8000/auth/callback", callbackUrl.ToString());

        // Replay the callback against the gateway (the browser would hit localhost:8000).
        var callback = await client.GetAsync(callbackUrl.PathAndQuery);
        Assert.Equal(HttpStatusCode.Found, callback.StatusCode);
        Assert.Equal("/", callback.Headers.Location!.ToString());
        if (ExtractSetCookie(callback, "stackverse_session") is null)
        {
            throw new InvalidOperationException("callback did not set the session cookie");
        }

        return xsrfToken;
    }

    public static string? ExtractSetCookie(HttpResponseMessage response, string cookieName)
    {
        if (!response.Headers.TryGetValues("Set-Cookie", out var cookies))
        {
            return null;
        }
        var match = cookies.FirstOrDefault(c => c.StartsWith(cookieName + "="));
        return match?[(cookieName.Length + 1)..match.IndexOf(';')];
    }
}
