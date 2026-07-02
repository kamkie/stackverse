using System.Net;
using System.Text;
using System.Text.Json;
using System.Text.RegularExpressions;
using Microsoft.AspNetCore.Mvc.Testing;

namespace StackverseGateway.Tests;

public sealed class GatewayTests(GatewayFixture fixture) : IClassFixture<GatewayFixture>
{
    private HttpClient CreateClient() => fixture.Factory.CreateClient(
        new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, HandleCookies = true });

    [Fact]
    public async Task Session_endpoint_reports_unauthenticated_without_a_session()
    {
        using var client = CreateClient();

        var response = await client.GetAsync("/auth/session");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        var session = JsonDocument.Parse(await response.Content.ReadAsStringAsync()).RootElement;
        Assert.False(session.GetProperty("authenticated").GetBoolean());
        Assert.False(session.TryGetProperty("username", out _));
    }

    [Fact]
    public async Task Api_without_a_session_returns_401_problem_document_not_a_redirect()
    {
        using var client = CreateClient();

        var response = await client.GetAsync("/api/v1/bookmarks");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        var problem = JsonDocument.Parse(await response.Content.ReadAsStringAsync()).RootElement;
        Assert.Equal(401, problem.GetProperty("status").GetInt32());
    }

    [Fact]
    public async Task Login_redirects_to_keycloak_with_code_flow_and_pkce()
    {
        using var client = CreateClient();

        var response = await client.GetAsync("/auth/login");

        Assert.Equal(HttpStatusCode.Found, response.StatusCode);
        var location = response.Headers.Location!.ToString();
        Assert.StartsWith(fixture.KeycloakBaseUrl + "/realms/stackverse/protocol/openid-connect/auth", location);
        Assert.Contains("response_type=code", location);
        Assert.Contains("code_challenge_method=S256", location);
        Assert.Contains("redirect_uri=" + Uri.EscapeDataString("http://localhost:8000/auth/callback"), location);
    }

    [Fact]
    public async Task Full_journey_login_relay_csrf_logout()
    {
        using var client = CreateClient();

        // --- Log in: /auth/login → Keycloak form → callback → session cookie.
        var xsrfToken = await LogInAsync(client, "demo", "demo");

        var session = await GetJsonAsync(client, "/auth/session");
        Assert.True(session.GetProperty("authenticated").GetBoolean());
        Assert.Equal("demo", session.GetProperty("username").GetString());

        // --- Token relay: GET /api/** reaches the backend with a Bearer token.
        var listResponse = await client.GetAsync("/api/v1/bookmarks");
        Assert.Equal(HttpStatusCode.OK, listResponse.StatusCode);
        Assert.Equal("/api/v1/bookmarks", fixture.Backend.LastPath);
        Assert.StartsWith("Bearer ", fixture.Backend.LastAuthorization);
        Assert.Equal("", fixture.Backend.LastCookie); // browser cookies never leave the gateway
        var jwtPayload = DecodeJwtPayload(fixture.Backend.LastAuthorization!["Bearer ".Length..]);
        Assert.Equal("demo", jwtPayload.GetProperty("preferred_username").GetString());
        Assert.Contains("stackverse-api", jwtPayload.GetProperty("aud").ToString());

        // --- CSRF: state-changing requests need the double-submit header.
        var missingHeader = await client.PostAsync("/api/v1/bookmarks", JsonContent(new { url = "https://example.com" }));
        Assert.Equal(HttpStatusCode.Forbidden, missingHeader.StatusCode);
        Assert.Equal("application/problem+json", missingHeader.Content.Headers.ContentType?.MediaType);

        using (var mismatch = new HttpRequestMessage(HttpMethod.Post, "/api/v1/bookmarks"))
        {
            mismatch.Headers.Add("X-XSRF-TOKEN", "not-the-cookie-value");
            mismatch.Content = JsonContent(new { url = "https://example.com" });
            var mismatchResponse = await client.SendAsync(mismatch);
            Assert.Equal(HttpStatusCode.Forbidden, mismatchResponse.StatusCode);
        }

        using (var valid = new HttpRequestMessage(HttpMethod.Post, "/api/v1/bookmarks"))
        {
            valid.Headers.Add("X-XSRF-TOKEN", xsrfToken);
            valid.Content = JsonContent(new { url = "https://example.com" });
            var validResponse = await client.SendAsync(valid);
            Assert.Equal(HttpStatusCode.OK, validResponse.StatusCode);
            Assert.StartsWith("Bearer ", fixture.Backend.LastAuthorization);
            Assert.Equal("", fixture.Backend.LastCsrfHeader); // consumed at the gateway
        }

        // --- Logout destroys the session and answers 204.
        var logoutResponse = await client.PostAsync("/auth/logout", null);
        Assert.Equal(HttpStatusCode.NoContent, logoutResponse.StatusCode);

        var afterLogout = await GetJsonAsync(client, "/auth/session");
        Assert.False(afterLogout.GetProperty("authenticated").GetBoolean());

        var apiAfterLogout = await client.GetAsync("/api/v1/bookmarks");
        Assert.Equal(HttpStatusCode.Unauthorized, apiAfterLogout.StatusCode);
    }

    /// <summary>
    /// Drives the real authorization code flow: follows the challenge to the Keycloak
    /// container, submits the login form like a browser would, then replays the
    /// callback against the gateway. Returns the XSRF-TOKEN issued along the way.
    /// </summary>
    private async Task<string> LogInAsync(HttpClient client, string username, string password)
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
        Assert.Contains("stackverse_session=", ExtractSetCookie(callback, "stackverse_session") is not null
            ? "stackverse_session=present"
            : throw new InvalidOperationException("callback did not set the session cookie"));

        return xsrfToken;
    }

    private static string? ExtractSetCookie(HttpResponseMessage response, string cookieName)
    {
        if (!response.Headers.TryGetValues("Set-Cookie", out var cookies))
        {
            return null;
        }
        var match = cookies.FirstOrDefault(c => c.StartsWith(cookieName + "="));
        return match?[(cookieName.Length + 1)..match.IndexOf(';')];
    }

    private static async Task<JsonElement> GetJsonAsync(HttpClient client, string path)
    {
        var response = await client.GetAsync(path);
        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        return JsonDocument.Parse(await response.Content.ReadAsStringAsync()).RootElement;
    }

    private static JsonElement DecodeJwtPayload(string jwt)
    {
        var payload = jwt.Split('.')[1].Replace('-', '+').Replace('_', '/');
        payload = payload.PadRight(payload.Length + (4 - payload.Length % 4) % 4, '=');
        return JsonDocument.Parse(Encoding.UTF8.GetString(Convert.FromBase64String(payload))).RootElement;
    }

    private static StringContent JsonContent(object value) =>
        new(JsonSerializer.Serialize(value), Encoding.UTF8, "application/json");
}
