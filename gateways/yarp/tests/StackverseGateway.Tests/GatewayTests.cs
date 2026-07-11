using System.Globalization;
using System.Net;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using StackExchange.Redis;

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
    public async Task Frontend_catch_all_proxies_spa_routes_without_leaking_gateway_cookies()
    {
        using var client = CreateClient();

        await client.GetAsync("/auth/session"); // issue gateway-owned cookies
        var response = await client.GetAsync("/admin/users");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Contains("Stackverse frontend stub", await response.Content.ReadAsStringAsync());
        Assert.Equal("/admin/users", fixture.Frontend.LastPath);
        Assert.Equal("", fixture.Frontend.LastCookie);
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
    public async Task Cross_origin_preflight_is_not_honored_as_cors()
    {
        using var client = CreateClient();
        using var request = new HttpRequestMessage(HttpMethod.Options, "/api/v1/bookmarks");
        request.Headers.Add("Origin", "https://evil.example");
        request.Headers.Add("Access-Control-Request-Method", "POST");

        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        AssertNoAccessControlAllowHeaders(response);
    }

    [Fact]
    public async Task Foreign_origin_rejects_state_changing_api_requests_even_when_csrf_passes()
    {
        using var client = CreateClient();
        var xsrf = await IssueCsrfTokenAsync(client);
        using var request = StateChangingRequest(xsrf);
        request.Headers.Add("Origin", "https://evil.example");

        var response = await client.SendAsync(request);

        await AssertCrossOriginForbiddenAsync(response);
    }

    [Theory]
    [InlineData("same-site")]
    [InlineData("cross-site")]
    public async Task Same_site_and_cross_site_fetch_metadata_reject_state_changing_api_requests(string fetchSite)
    {
        using var client = CreateClient();
        var xsrf = await IssueCsrfTokenAsync(client);
        using var request = StateChangingRequest(xsrf);
        request.Headers.Add("Sec-Fetch-Site", fetchSite);

        var response = await client.SendAsync(request);

        await AssertCrossOriginForbiddenAsync(response);
    }

    [Fact]
    public async Task One_failing_same_origin_signal_rejects_even_when_the_other_passes()
    {
        using (var client = CreateClient())
        {
            var xsrf = await IssueCsrfTokenAsync(client);
            using var request = StateChangingRequest(xsrf);
            request.Headers.Add("Origin", "http://localhost:8000");
            request.Headers.Add("Sec-Fetch-Site", "same-site");

            await AssertCrossOriginForbiddenAsync(await client.SendAsync(request));
        }

        using (var client = CreateClient())
        {
            var xsrf = await IssueCsrfTokenAsync(client);
            using var request = StateChangingRequest(xsrf);
            request.Headers.Add("Origin", "https://evil.example");
            request.Headers.Add("Sec-Fetch-Site", "same-origin");

            await AssertCrossOriginForbiddenAsync(await client.SendAsync(request));
        }
    }

    [Fact]
    public async Task Same_origin_none_and_absent_browser_signals_are_allowed_when_csrf_passes()
    {
        var cases = new (string Name, string Value)[][]
        {
            new[] { ("Origin", "http://localhost:8000") },
            new[] { ("Sec-Fetch-Site", "same-origin") },
            new[] { ("Sec-Fetch-Site", "none") },
            Array.Empty<(string Name, string Value)>(),
        };

        foreach (var headers in cases)
        {
            using var client = CreateClient();
            var xsrf = await IssueCsrfTokenAsync(client);
            using var request = StateChangingRequest(xsrf);
            foreach (var (name, value) in headers)
            {
                request.Headers.Add(name, value);
            }

            var response = await client.SendAsync(request);

            Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        }
    }

    [Fact]
    public async Task Security_headers_are_scoped_without_changing_api_cache_semantics()
    {
        using var client = CreateClient();

        var spa = await client.GetAsync("/");
        Assert.Equal(HttpStatusCode.OK, spa.StatusCode);
        AssertDocumentSecurityHeaders(spa, expectHsts: false);

        var auth = await client.GetAsync("/auth/session");
        Assert.Equal(HttpStatusCode.OK, auth.StatusCode);
        AssertDocumentSecurityHeaders(auth, expectHsts: false);

        var login = await client.GetAsync("/auth/login");
        Assert.Equal(HttpStatusCode.Found, login.StatusCode);
        AssertDocumentSecurityHeaders(login, expectHsts: false);

        var api = await client.GetAsync("/api/v1/messages/bundle");
        Assert.Equal(HttpStatusCode.OK, api.StatusCode);
        AssertApiSecurityHeaders(api, expectHsts: false);
        AssertHeader(api, "Cache-Control", "no-cache");
        Assert.Equal("\"bundle-v1\"", api.Headers.ETag?.ToString());

        using var revalidate = new HttpRequestMessage(HttpMethod.Get, "/api/v1/messages/bundle");
        revalidate.Headers.TryAddWithoutValidation("If-None-Match", "\"bundle-v1\"");
        var notModified = await client.SendAsync(revalidate);
        Assert.Equal(HttpStatusCode.NotModified, notModified.StatusCode);
        AssertApiSecurityHeaders(notModified, expectHsts: false);
        AssertHeader(notModified, "Cache-Control", "no-cache");
        Assert.Equal("\"bundle-v1\"", notModified.Headers.ETag?.ToString());
        Assert.Equal("", await notModified.Content.ReadAsStringAsync());
    }

    [Fact]
    public async Task Backend_unauthorized_problem_is_passed_through_without_an_oidc_redirect()
    {
        using var client = CreateClient();
        const string body = """
            {"type":"about:blank","title":"Unauthorized","status":401,"detail":"A bearer token is required."}
            """;
        fixture.Backend.RespondOnce(HttpStatusCode.Unauthorized, "application/problem+json", body);

        var response = await client.GetAsync("/api/v1/me");

        Assert.Equal(HttpStatusCode.Unauthorized, response.StatusCode);
        Assert.Null(response.Headers.Location);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        Assert.Equal(body, await response.Content.ReadAsStringAsync());
        Assert.Equal("", fixture.Backend.LastAuthorization);
    }

    [Fact]
    public async Task Backend_validation_problem_and_request_body_cross_the_proxy_unchanged()
    {
        using var client = CreateClient();
        var xsrf = await IssueCsrfTokenAsync(client);
        const string problem = """
            {"type":"about:blank","title":"Validation failed","status":400,"errors":[{"field":"url","messageKey":"validation.url","message":"Invalid URL"}]}
            """;
        const string requestBody = "{\"url\":\"not-a-url\",\"title\":\"Example\"}";
        fixture.Backend.RespondOnce(HttpStatusCode.BadRequest, "application/problem+json", problem);
        using var request = new HttpRequestMessage(HttpMethod.Post, "/api/v1/bookmarks?source=browser")
        {
            Content = new StringContent(requestBody, Encoding.UTF8, "application/json"),
        };
        request.Headers.Add(Csrf.HeaderName, xsrf);

        var response = await client.SendAsync(request);

        Assert.Equal(HttpStatusCode.BadRequest, response.StatusCode);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        Assert.Equal(problem, await response.Content.ReadAsStringAsync());
        Assert.Equal("POST", fixture.Backend.LastMethod);
        Assert.Equal("/api/v1/bookmarks", fixture.Backend.LastPath);
        Assert.Equal("?source=browser", fixture.Backend.LastQuery);
        Assert.Equal(requestBody, fixture.Backend.LastBody);
        Assert.Equal("", fixture.Backend.LastAuthorization);
        Assert.Equal("", fixture.Backend.LastCookie);
        Assert.Equal("", fixture.Backend.LastCsrfHeader);
    }

    [Fact]
    public async Task Authenticated_moderation_request_relay_and_backend_denial_stay_at_their_boundaries()
    {
        using var client = CreateClient();
        var xsrf = await AuthFlows.LogInAsync(client, "demo", "demo");
        try
        {
            const string problem = """
                {"type":"about:blank","title":"Forbidden","status":403,"detail":"The moderator role is required."}
                """;
            const string requestBody = "{\"resolution\":\"actioned\",\"note\":\"duplicate reports\"}";
            fixture.Backend.RespondOnce(HttpStatusCode.Forbidden, "application/problem+json", problem);
            using var request = new HttpRequestMessage(
                HttpMethod.Put,
                "/api/v1/admin/reports/00000000-0000-0000-0000-000000000001")
            {
                Content = new StringContent(requestBody, Encoding.UTF8, "application/json"),
            };
            request.Headers.Add(Csrf.HeaderName, xsrf);
            request.Headers.TryAddWithoutValidation("Authorization", "Bearer forged-by-the-client");

            var response = await client.SendAsync(request);

            Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
            Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
            Assert.Equal(problem, await response.Content.ReadAsStringAsync());
            Assert.Equal("PUT", fixture.Backend.LastMethod);
            Assert.Equal(
                "/api/v1/admin/reports/00000000-0000-0000-0000-000000000001",
                fixture.Backend.LastPath);
            Assert.Equal(requestBody, fixture.Backend.LastBody);
            Assert.StartsWith("Bearer ", fixture.Backend.LastAuthorization);
            Assert.DoesNotContain("forged-by-the-client", fixture.Backend.LastAuthorization, StringComparison.Ordinal);
            Assert.Equal("", fixture.Backend.LastCookie);
            Assert.Equal("", fixture.Backend.LastCsrfHeader);
        }
        finally
        {
            using var logoutResponse = await client.PostAsync("/auth/logout", null);
        }
    }

    [Fact]
    public async Task Backend_versioning_and_localization_headers_are_preserved()
    {
        using var client = CreateClient();
        const string body = "{\"items\":[],\"page\":0,\"size\":20,\"totalItems\":0,\"totalPages\":0}";
        fixture.Backend.RespondOnce(
            HttpStatusCode.OK,
            "application/json",
            body,
            new Dictionary<string, string>
            {
                ["Content-Language"] = "pl",
                ["Deprecation"] = "@1782864000",
                ["Sunset"] = "Thu, 01 Jul 2027 00:00:00 GMT",
                ["Link"] = "</api/v2/bookmarks>; rel=\"successor-version\"",
            });

        var response = await client.GetAsync("/api/v1/bookmarks");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal(body, await response.Content.ReadAsStringAsync());
        Assert.Equal("pl", Assert.Single(response.Content.Headers.ContentLanguage));
        AssertHeader(response, "Deprecation", "@1782864000");
        AssertHeader(response, "Sunset", "Thu, 01 Jul 2027 00:00:00 GMT");
        AssertHeader(response, "Link", "</api/v2/bookmarks>; rel=\"successor-version\"");
    }

    [Fact]
    public async Task Static_spa_fallback_serves_root_and_deep_links_without_replacing_the_api_proxy()
    {
        using var factory = fixture.Factory.WithWebHostBuilder(builder =>
            builder.UseSetting("FRONTEND_URL", ""));
        using var client = factory.CreateClient(
            new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, HandleCookies = true });

        var root = await client.GetAsync("/");
        var deepLink = await client.GetAsync("/admin/reports");
        var api = await client.GetAsync("/api/v2/bookmarks?visibility=public");

        Assert.Equal(HttpStatusCode.OK, root.StatusCode);
        Assert.Contains("Stackverse", await root.Content.ReadAsStringAsync(), StringComparison.Ordinal);
        Assert.Equal(HttpStatusCode.OK, deepLink.StatusCode);
        Assert.Contains("Stackverse", await deepLink.Content.ReadAsStringAsync(), StringComparison.Ordinal);
        Assert.Equal(HttpStatusCode.OK, api.StatusCode);
        Assert.Equal("/api/v2/bookmarks", fixture.Backend.LastPath);
    }

    [Fact]
    public async Task Hsts_is_emitted_only_when_public_url_is_https()
    {
        using var httpClient = CreateClient();
        var http = await httpClient.GetAsync("/auth/session");
        AssertDocumentSecurityHeaders(http, expectHsts: false);

        using var httpsFactory = fixture.Factory.WithWebHostBuilder(builder =>
            builder.UseSetting("PUBLIC_URL", "https://stackverse.example"));
        using var httpsClient = httpsFactory.CreateClient(
            new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, HandleCookies = true });
        var https = await httpsClient.GetAsync("/auth/session");

        AssertDocumentSecurityHeaders(https, expectHsts: true);
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

    [Fact]
    public async Task Idp_rejection_during_refresh_destroys_the_session_and_relays_anonymously()
    {
        using var client = CreateClient();
        await AuthFlows.LogInAsync(client, "demo", "demo");

        var services = fixture.Factory.Services;
        var server = services.GetRequiredService<IConnectionMultiplexer>().GetServers().Single();
        var sessionKey = server.Keys(pattern: "stackverse:session:*").Single().ToString();
        var store = services.GetRequiredService<RedisTicketStore>();
        var ticket = (await store.RetrieveAsync(sessionKey))!;
        ticket.Properties.UpdateTokenValue(
            "expires_at",
            DateTimeOffset.UtcNow.AddMinutes(-5).ToString("o", CultureInfo.InvariantCulture));
        ticket.Properties.UpdateTokenValue("refresh_token", "invalid-refresh-token");
        await store.RenewAsync(sessionKey, ticket);

        var response = await client.GetAsync("/api/v1/bookmarks");

        Assert.Equal(HttpStatusCode.OK, response.StatusCode);
        Assert.Equal("", fixture.Backend.LastAuthorization);
        Assert.NotNull(AuthFlows.ExtractSetCookie(response, "stackverse_session"));
        Assert.Null(await store.RetrieveAsync(sessionKey));
        var session = await GetJsonAsync(client, "/auth/session");
        Assert.False(session.GetProperty("authenticated").GetBoolean());
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

    private static async Task<string> IssueCsrfTokenAsync(HttpClient client)
    {
        var response = await client.GetAsync("/auth/session");
        return AuthFlows.ExtractSetCookie(response, "XSRF-TOKEN")
            ?? throw new InvalidOperationException("gateway did not issue an XSRF-TOKEN cookie");
    }

    private static HttpRequestMessage StateChangingRequest(string xsrf)
    {
        var request = new HttpRequestMessage(HttpMethod.Post, "/api/v1/bookmarks")
        {
            Content = JsonContent(new { url = "https://example.com" }),
        };
        request.Headers.Add("X-XSRF-TOKEN", xsrf);
        return request;
    }

    private static async Task AssertCrossOriginForbiddenAsync(HttpResponseMessage response)
    {
        Assert.Equal(HttpStatusCode.Forbidden, response.StatusCode);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        Assert.Contains(
            "Cross-origin state-changing requests are not supported.",
            await response.Content.ReadAsStringAsync());
        AssertNoAccessControlAllowHeaders(response);
    }

    private static void AssertNoAccessControlAllowHeaders(HttpResponseMessage response)
    {
        var headerNames = response.Headers.Select(header => header.Key)
            .Concat(response.Content.Headers.Select(header => header.Key));
        Assert.DoesNotContain(headerNames, name =>
            name.StartsWith("Access-Control-Allow", StringComparison.OrdinalIgnoreCase));
    }

    private static void AssertDocumentSecurityHeaders(HttpResponseMessage response, bool expectHsts)
    {
        AssertHeader(response, "X-Content-Type-Options", "nosniff");
        AssertHeader(response, "Referrer-Policy", "same-origin");
        AssertHeader(response, "Content-Security-Policy",
            "default-src 'self'; base-uri 'self'; object-src 'none'; frame-ancestors 'none'");
        AssertHeader(response, "X-Frame-Options", "DENY");
        AssertHeader(response, "Cross-Origin-Opener-Policy", "same-origin");
        AssertHeader(response, "Cross-Origin-Resource-Policy", "same-origin");
        AssertHsts(response, expectHsts);
    }

    private static void AssertApiSecurityHeaders(HttpResponseMessage response, bool expectHsts)
    {
        AssertHeader(response, "X-Content-Type-Options", "nosniff");
        AssertHeaderAbsent(response, "Referrer-Policy");
        AssertHeaderAbsent(response, "Content-Security-Policy");
        AssertHeaderAbsent(response, "X-Frame-Options");
        AssertHeaderAbsent(response, "Cross-Origin-Opener-Policy");
        AssertHeaderAbsent(response, "Cross-Origin-Resource-Policy");
        AssertHsts(response, expectHsts);
    }

    private static void AssertHsts(HttpResponseMessage response, bool expected)
    {
        if (expected)
        {
            AssertHeader(response, "Strict-Transport-Security", EdgeSecurity.StrictTransportSecurity);
        }
        else
        {
            AssertHeaderAbsent(response, "Strict-Transport-Security");
        }
    }

    private static void AssertHeader(HttpResponseMessage response, string name, string value)
    {
        Assert.True(response.Headers.TryGetValues(name, out var values), $"{name} should be present");
        Assert.Equal(value, Assert.Single(values));
    }

    private static void AssertHeaderAbsent(HttpResponseMessage response, string name)
    {
        Assert.False(response.Headers.Contains(name), $"{name} should be absent");
    }
}
