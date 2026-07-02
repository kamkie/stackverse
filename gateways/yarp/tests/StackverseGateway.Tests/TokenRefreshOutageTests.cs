using System.Globalization;
using System.Net;
using System.Text.Json;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Mvc.Testing;
using Microsoft.Extensions.DependencyInjection;
using StackExchange.Redis;

namespace StackverseGateway.Tests;

/// <summary>
/// The IdP-unreachable refresh path (docs/ARCHITECTURE.md): a transient Keycloak
/// outage must fail the request with a 503 problem document while the session —
/// whose refresh token may still be perfectly valid — survives. Gets its own
/// fixture (class fixtures are per-class) because it kills the Keycloak container.
/// </summary>
public sealed class TokenRefreshOutageTests(GatewayFixture fixture) : IClassFixture<GatewayFixture>
{
    [Fact]
    public async Task Idp_outage_during_refresh_fails_the_request_but_keeps_the_session()
    {
        using var client = fixture.Factory.CreateClient(
            new WebApplicationFactoryClientOptions { AllowAutoRedirect = false, HandleCookies = true });
        await AuthFlows.LogInAsync(client, "demo", "demo");

        // Force the next /api call down the refresh path: rewrite the stored ticket's
        // access-token expiry into the past, through the gateway's own ticket store.
        var services = fixture.Factory.Services;
        var server = services.GetRequiredService<IConnectionMultiplexer>().GetServers().Single();
        var sessionKey = server.Keys(pattern: "stackverse:session:*").Single().ToString();
        var store = services.GetRequiredService<RedisTicketStore>();
        var ticket = (await store.RetrieveAsync(sessionKey))!;
        ticket.Properties.UpdateTokenValue(
            "expires_at", DateTimeOffset.UtcNow.AddMinutes(-5).ToString("o", CultureInfo.InvariantCulture));
        await store.RenewAsync(sessionKey, ticket);

        await fixture.StopKeycloakAsync();

        // The request fails explicitly — a 503 problem document, not an unhandled 500
        // and not an anonymous relay the user never asked for.
        var response = await client.GetAsync("/api/v1/bookmarks");
        Assert.Equal(HttpStatusCode.ServiceUnavailable, response.StatusCode);
        Assert.Equal("application/problem+json", response.Content.Headers.ContentType?.MediaType);
        var problem = JsonDocument.Parse(await response.Content.ReadAsStringAsync()).RootElement;
        Assert.Equal(503, problem.GetProperty("status").GetInt32());

        // The session survived the outage: the IdP said nothing about it.
        var session = await client.GetAsync("/auth/session");
        Assert.Equal(HttpStatusCode.OK, session.StatusCode);
        var body = JsonDocument.Parse(await session.Content.ReadAsStringAsync()).RootElement;
        Assert.True(body.GetProperty("authenticated").GetBoolean());
        Assert.Equal("demo", body.GetProperty("username").GetString());
    }
}
