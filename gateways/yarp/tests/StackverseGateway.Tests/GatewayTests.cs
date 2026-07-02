using System.Net;
using System.Text;
using System.Text.Json;
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
    public async Task Anonymous_api_requests_relay_without_a_bearer_token()
    {
        using var client = CreateClient();

        // The spec's public surface (public bookmark feeds, message reads) must work
        // logged-out: the gateway relays and the backend decides per endpoint. A
        // client-supplied Authorization header must be stripped, not relayed — the
        // gateway session is the only source of upstream identity.
        using var request = new HttpRequestMessage(HttpMethod.Get, "/api/v2/bookmarks?visibility=public");
        request.Headers.TryAddWithoutValidation("Authorization", "Bearer forged-by-the-client");
        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("", fixture.Backend.LastAuthorization);
        Assert.Equal("", fixture.Backend.LastCookie);
    }

    [Fact]
    public async Task Anonymous_state_changing_requests_still_require_the_csrf_header()
    {
        using var client = CreateClient();

        var response = await client.PostAsync("/api/v1/bookmarks", JsonContent(new { url = "https://example.com" }));

        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        var problem = JsonDocument.Parse(await response.Content.ReadAsStringAsync()).RootElement;
        Assert.Equal(403, problem.GetProperty("status").GetInt32());
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

    // A failed callback is expected client/IdP behavior (contract: redirect to /,
    // never a 5xx — docs/ARCHITECTURE.md): the user pressed Cancel on the Keycloak
    // form, or the correlation state is stale or replayed.
    [Theory]
    [InlineData("/auth/callback?error=access_denied&state=whatever")] // user cancelled at the IdP
    [InlineData("/auth/callback?code=fake-code&state=not-a-real-state")] // stale/invalid correlation
    public async Task Failed_callback_redirects_home_without_a_session(string callbackPath)
    {
        using var client = CreateClient();

        var response = await client.GetAsync(callbackPath);

        Assert.Equal(HttpStatusCode.Found, response.StatusCode);
        Assert.Equal("/", response.Headers.Location!.ToString());
        Assert.Null(AuthFlows.ExtractSetCookie(response, "stackverse_session"));

        var session = await GetJsonAsync(client, "/auth/session");
        Assert.False(session.GetProperty("authenticated").GetBoolean());
    }

    [Fact]
    public async Task Full_journey_login_relay_csrf_logout()
    {
        using var client = CreateClient();

        // --- Log in: /auth/login → Keycloak form → callback → session cookie.
        var xsrfToken = await AuthFlows.LogInAsync(client, "demo", "demo");

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

        // The dead session no longer yields a token — the relay degrades to anonymous.
        var apiAfterLogout = await client.GetAsync("/api/v1/bookmarks");
        Assert.Equal(HttpStatusCode.OK, apiAfterLogout.StatusCode);
        Assert.Equal("", fixture.Backend.LastAuthorization);
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
