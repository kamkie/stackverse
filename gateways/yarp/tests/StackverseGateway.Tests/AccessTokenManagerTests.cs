using System.Globalization;
using System.Net;
using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.OpenIdConnect;
using Microsoft.Extensions.Caching.Distributed;
using Microsoft.Extensions.Caching.Memory;
using Microsoft.Extensions.Logging.Abstractions;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;

namespace StackverseGateway.Tests;

/// <summary>
/// Pins the refresh-failure taxonomy from docs/ARCHITECTURE.md at the unit level:
/// only an authoritative 400/401 from the token endpoint is a rejection (null →
/// the caller destroys the session); everything else — 5xx, 429, network failure —
/// is an IdP outage that must throw so the session survives.
/// </summary>
public sealed class AccessTokenManagerTests
{
    [Fact]
    public async Task Fresh_access_token_is_returned_without_refreshing()
    {
        var manager = CreateManager(new StubHandler((_, _) => throw new InvalidOperationException("refresh not expected")));
        var auth = SessionAuth(expiresAt: DateTimeOffset.UtcNow.AddMinutes(5));

        var token = await manager.GetAccessTokenAsync(auth, CancellationToken.None);

        Assert.Equal("access-token", token);
    }

    [Fact]
    public async Task Missing_access_token_yields_null()
    {
        var manager = CreateManager(new StubHandler((_, _) => throw new InvalidOperationException("refresh not expected")));
        var properties = new AuthenticationProperties();
        properties.StoreTokens(
        [
            new AuthenticationToken { Name = "refresh_token", Value = "refresh-token" },
            new AuthenticationToken
            {
                Name = "expires_at",
                Value = DateTimeOffset.UtcNow.AddMinutes(-5).ToString("o", CultureInfo.InvariantCulture),
            },
        ]);
        var auth = AuthenticateResult.Success(new AuthenticationTicket(TestPrincipal(), properties, "Cookies"));

        var token = await manager.GetAccessTokenAsync(auth, CancellationToken.None);

        Assert.Null(token);
    }

    [Fact]
    public async Task Expired_session_without_refresh_token_yields_null()
    {
        var manager = CreateManager(new StubHandler((_, _) => throw new InvalidOperationException("refresh not expected")));
        var properties = new AuthenticationProperties();
        properties.StoreTokens(
        [
            new AuthenticationToken { Name = "access_token", Value = "expired-access-token" },
            new AuthenticationToken
            {
                Name = "expires_at",
                Value = DateTimeOffset.UtcNow.AddMinutes(-5).ToString("o", CultureInfo.InvariantCulture),
            },
        ]);
        var auth = AuthenticateResult.Success(new AuthenticationTicket(TestPrincipal(), properties, "Cookies"));

        var token = await manager.GetAccessTokenAsync(auth, CancellationToken.None);

        Assert.Null(token);
    }

    [Theory]
    [InlineData(HttpStatusCode.BadRequest)] // RFC 6749 §5.2: invalid_grant (expired/revoked refresh token)
    [InlineData(HttpStatusCode.Unauthorized)] // client authentication failed
    public async Task Idp_rejection_yields_null_so_the_caller_destroys_the_session(HttpStatusCode status)
    {
        var manager = CreateManager(new StubHandler((_, _) => new HttpResponseMessage(status)));

        var token = await manager.GetAccessTokenAsync(ExpiredSessionAuth(), CancellationToken.None);

        Assert.Null(token);
    }

    [Theory]
    [InlineData(HttpStatusCode.InternalServerError)]
    [InlineData(HttpStatusCode.BadGateway)]
    [InlineData(HttpStatusCode.ServiceUnavailable)]
    [InlineData(HttpStatusCode.TooManyRequests)]
    public async Task Idp_failure_statuses_are_an_outage_not_a_rejection(HttpStatusCode status)
    {
        var manager = CreateManager(new StubHandler((_, _) => new HttpResponseMessage(status)));

        await Assert.ThrowsAsync<IdpUnavailableException>(
            () => manager.GetAccessTokenAsync(ExpiredSessionAuth(), CancellationToken.None));
    }

    [Fact]
    public async Task Network_failure_is_an_outage()
    {
        var manager = CreateManager(new StubHandler((_, _) => throw new HttpRequestException("connection refused")));

        await Assert.ThrowsAsync<IdpUnavailableException>(
            () => manager.GetAccessTokenAsync(ExpiredSessionAuth(), CancellationToken.None));
    }

    [Fact]
    public async Task Client_abort_is_not_reported_as_an_idp_outage()
    {
        using var cts = new CancellationTokenSource();
        await cts.CancelAsync();
        var manager = CreateManager(new StubHandler((_, ct) => throw new OperationCanceledException(ct)));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => manager.GetAccessTokenAsync(ExpiredSessionAuth(), cts.Token));
    }

    [Fact]
    public async Task Successful_refresh_updates_the_authentication_properties()
    {
        var manager = CreateManager(new StubHandler((_, _) => JsonResponse("""
            {"access_token":"new-access-token","refresh_token":"new-refresh-token","expires_in":120}
            """)));
        var auth = ExpiredSessionAuth();

        var token = await manager.GetAccessTokenAsync(auth, CancellationToken.None);

        var properties = auth.Properties!;
        Assert.Equal("new-access-token", token);
        Assert.Equal("new-access-token", properties.GetTokenValue("access_token"));
        Assert.Equal("new-refresh-token", properties.GetTokenValue("refresh_token"));
        Assert.True(DateTimeOffset.Parse(
            properties.GetTokenValue("expires_at")!,
            CultureInfo.InvariantCulture,
            DateTimeStyles.RoundtripKind) > DateTimeOffset.UtcNow.AddSeconds(60));
    }

    [Fact]
    public async Task Successful_refresh_keeps_the_existing_refresh_token_when_the_idp_does_not_rotate_it()
    {
        var manager = CreateManager(new StubHandler((_, _) => JsonResponse("""
            {"access_token":"new-access-token","expires_in":120}
            """)));
        var auth = ExpiredSessionAuth();

        await manager.GetAccessTokenAsync(auth, CancellationToken.None);

        Assert.Equal("refresh-token", auth.Properties!.GetTokenValue("refresh_token"));
    }

    [Fact]
    public async Task Successful_refresh_renews_the_redis_ticket_when_the_session_key_is_known()
    {
        var cache = new MemoryDistributedCache(Options.Create(new MemoryDistributedCacheOptions()));
        var ticketStore = new RedisTicketStore(cache);
        var sessionKey = await ticketStore.StoreAsync(ExpiredSessionTicket());
        var ticket = (await ticketStore.RetrieveAsync(sessionKey))!;
        var auth = AuthenticateResult.Success(ticket);
        var manager = CreateManager(
            new StubHandler((_, _) => JsonResponse("""
                {"access_token":"persisted-access-token","refresh_token":"persisted-refresh-token","expires_in":120}
            """)),
            ticketStore);

        await manager.GetAccessTokenAsync(auth, CancellationToken.None);

        var renewed = (await ticketStore.RetrieveAsync(sessionKey))!;
        Assert.Equal("persisted-access-token", renewed.Properties.GetTokenValue("access_token"));
        Assert.Equal("persisted-refresh-token", renewed.Properties.GetTokenValue("refresh_token"));
    }

    [Fact]
    public async Task Refresh_request_uses_the_configured_token_endpoint_and_confidential_client_credentials()
    {
        HttpRequestMessage? captured = null;
        var manager = CreateManager(new StubHandler((request, _) =>
        {
            captured = request;
            return JsonResponse("""
                {"access_token":"new-access-token","refresh_token":"new-refresh-token","expires_in":120}
                """);
        }));

        await manager.GetAccessTokenAsync(ExpiredSessionAuth(), CancellationToken.None);

        Assert.Equal(HttpMethod.Post, captured!.Method);
        Assert.Equal("http://idp.invalid/token", captured.RequestUri!.ToString());
        var form = await captured.Content!.ReadAsStringAsync();
        Assert.Contains("grant_type=refresh_token", form);
        Assert.Contains("refresh_token=refresh-token", form);
        Assert.Contains("client_id=stackverse-gateway", form);
        Assert.Contains("client_secret=stackverse-secret", form);
    }

    private static AccessTokenManager CreateManager(HttpMessageHandler handler, RedisTicketStore? ticketStore = null)
    {
        var options = new OpenIdConnectOptions
        {
            ClientId = "stackverse-gateway",
            ClientSecret = "stackverse-secret",
            Backchannel = new HttpClient(handler),
            ConfigurationManager = new StaticConfigurationManager<OpenIdConnectConfiguration>(
                new OpenIdConnectConfiguration { TokenEndpoint = "http://idp.invalid/token" }),
        };
        var cache = new MemoryDistributedCache(Options.Create(new MemoryDistributedCacheOptions()));
        return new AccessTokenManager(
            new StaticOptionsMonitor(options),
            ticketStore ?? new RedisTicketStore(cache),
            NullLogger<AccessTokenManager>.Instance);
    }

    /// <summary>An authenticated session whose access token is past its expiry, forcing a refresh.</summary>
    private static AuthenticateResult ExpiredSessionAuth() => AuthenticateResult.Success(ExpiredSessionTicket());

    private static AuthenticateResult SessionAuth(DateTimeOffset expiresAt)
        => AuthenticateResult.Success(SessionTicket("access-token", "refresh-token", expiresAt));

    private static AuthenticationTicket ExpiredSessionTicket()
        => SessionTicket("expired-access-token", "refresh-token", DateTimeOffset.UtcNow.AddMinutes(-5));

    private static AuthenticationTicket SessionTicket(
        string accessToken,
        string refreshToken,
        DateTimeOffset expiresAt)
    {
        var properties = new AuthenticationProperties();
        properties.StoreTokens(
        [
            new AuthenticationToken { Name = "access_token", Value = accessToken },
            new AuthenticationToken { Name = "refresh_token", Value = refreshToken },
            new AuthenticationToken
            {
                Name = "expires_at",
                Value = expiresAt.ToString("o", CultureInfo.InvariantCulture),
            },
        ]);
        return new AuthenticationTicket(TestPrincipal(), properties, "Cookies");
    }

    private static ClaimsPrincipal TestPrincipal()
    {
        var identity = new ClaimsIdentity([new Claim("preferred_username", "demo")], "test");
        return new ClaimsPrincipal(identity);
    }

    private static HttpResponseMessage JsonResponse(string json) =>
        new(HttpStatusCode.OK) { Content = new StringContent(json) };

    private sealed class StubHandler(Func<HttpRequestMessage, CancellationToken, HttpResponseMessage> respond) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
            => Task.FromResult(respond(request, cancellationToken));
    }

    private sealed class StaticOptionsMonitor(OpenIdConnectOptions options) : IOptionsMonitor<OpenIdConnectOptions>
    {
        public OpenIdConnectOptions CurrentValue => options;

        public OpenIdConnectOptions Get(string? name) => options;

        public IDisposable? OnChange(Action<OpenIdConnectOptions, string?> listener) => null;
    }
}
