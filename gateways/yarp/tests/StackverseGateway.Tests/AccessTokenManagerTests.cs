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
    [Theory]
    [InlineData(HttpStatusCode.BadRequest)] // RFC 6749 §5.2: invalid_grant (expired/revoked refresh token)
    [InlineData(HttpStatusCode.Unauthorized)] // client authentication failed
    public async Task Idp_rejection_yields_null_so_the_caller_destroys_the_session(HttpStatusCode status)
    {
        var manager = CreateManager(new StubHandler(_ => new HttpResponseMessage(status)));

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
        var manager = CreateManager(new StubHandler(_ => new HttpResponseMessage(status)));

        await Assert.ThrowsAsync<IdpUnavailableException>(
            () => manager.GetAccessTokenAsync(ExpiredSessionAuth(), CancellationToken.None));
    }

    [Fact]
    public async Task Network_failure_is_an_outage()
    {
        var manager = CreateManager(new StubHandler(_ => throw new HttpRequestException("connection refused")));

        await Assert.ThrowsAsync<IdpUnavailableException>(
            () => manager.GetAccessTokenAsync(ExpiredSessionAuth(), CancellationToken.None));
    }

    [Fact]
    public async Task Client_abort_is_not_reported_as_an_idp_outage()
    {
        using var cts = new CancellationTokenSource();
        await cts.CancelAsync();
        var manager = CreateManager(new StubHandler(ct => throw new OperationCanceledException(ct)));

        await Assert.ThrowsAnyAsync<OperationCanceledException>(
            () => manager.GetAccessTokenAsync(ExpiredSessionAuth(), cts.Token));
    }

    private static AccessTokenManager CreateManager(HttpMessageHandler handler)
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
            new StaticOptionsMonitor(options), new RedisTicketStore(cache), NullLogger<AccessTokenManager>.Instance);
    }

    /// <summary>An authenticated session whose access token is past its expiry, forcing a refresh.</summary>
    private static AuthenticateResult ExpiredSessionAuth()
    {
        var properties = new AuthenticationProperties();
        properties.StoreTokens(
        [
            new AuthenticationToken { Name = "access_token", Value = "expired-access-token" },
            new AuthenticationToken { Name = "refresh_token", Value = "refresh-token" },
            new AuthenticationToken
            {
                Name = "expires_at",
                Value = DateTimeOffset.UtcNow.AddMinutes(-5).ToString("o", CultureInfo.InvariantCulture),
            },
        ]);
        var identity = new ClaimsIdentity([new Claim("preferred_username", "demo")], "test");
        return AuthenticateResult.Success(
            new AuthenticationTicket(new ClaimsPrincipal(identity), properties, "Cookies"));
    }

    private sealed class StubHandler(Func<CancellationToken, HttpResponseMessage> respond) : HttpMessageHandler
    {
        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request, CancellationToken cancellationToken)
            => Task.FromResult(respond(cancellationToken));
    }

    private sealed class StaticOptionsMonitor(OpenIdConnectOptions options) : IOptionsMonitor<OpenIdConnectOptions>
    {
        public OpenIdConnectOptions CurrentValue => options;

        public OpenIdConnectOptions Get(string? name) => options;

        public IDisposable? OnChange(Action<OpenIdConnectOptions, string?> listener) => null;
    }
}
