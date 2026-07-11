using System.Net;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.OpenIdConnect;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Options;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;

namespace StackverseGateway.Tests;

public sealed class RpInitiatedLogoutTests
{
    [Fact]
    public async Task Missing_refresh_token_skips_the_idp_call()
    {
        var handler = new StubHandler((_, _) => throw new InvalidOperationException("logout not expected"));
        var logger = new RecordingLogger<RpInitiatedLogout>();
        var logout = CreateLogout(handler, logger);

        await logout.LogoutAsync(new AuthenticationProperties(), CancellationToken.None);

        Assert.Empty(logger.Entries);
        Assert.Equal(0, handler.CallCount);
    }

    [Fact]
    public async Task Logout_posts_the_refresh_token_and_confidential_client_credentials()
    {
        HttpRequestMessage? captured = null;
        var handler = new StubHandler((request, _) =>
        {
            captured = request;
            return new HttpResponseMessage(HttpStatusCode.NoContent);
        });
        var logger = new RecordingLogger<RpInitiatedLogout>();
        var logout = CreateLogout(handler, logger);

        await logout.LogoutAsync(PropertiesWithRefreshToken(), CancellationToken.None);

        Assert.Equal(HttpMethod.Post, captured!.Method);
        Assert.Equal("http://idp.invalid/logout", captured.RequestUri!.ToString());
        var form = await captured.Content!.ReadAsStringAsync();
        Assert.Contains("client_id=stackverse-gateway", form);
        Assert.Contains("client_secret=client-secret", form);
        Assert.Contains("refresh_token=refresh-token", form);
        Assert.Empty(logger.Entries);
    }

    [Theory]
    [InlineData(HttpStatusCode.BadRequest)]
    [InlineData(HttpStatusCode.InternalServerError)]
    public async Task Idp_failure_status_is_best_effort_and_logged_without_secrets(HttpStatusCode status)
    {
        var logger = new RecordingLogger<RpInitiatedLogout>();
        var logout = CreateLogout(new StubHandler((_, _) => new HttpResponseMessage(status)), logger);

        await logout.LogoutAsync(PropertiesWithRefreshToken(), CancellationToken.None);

        var entry = Assert.Single(logger.Entries);
        Assert.Equal(LogLevel.Warning, entry.Level);
        Assert.Equal("idp_logout_failed", entry.Fields["event"]);
        Assert.Equal("failure", entry.Fields["outcome"]);
        Assert.Equal("idp_rejected", entry.Fields["error_code"]);
        Assert.Equal((int)status, entry.Fields["idp_status"]);
        Assert.DoesNotContain("refresh-token", LogText(entry), StringComparison.Ordinal);
        Assert.DoesNotContain("client-secret", LogText(entry), StringComparison.Ordinal);
    }

    [Fact]
    public async Task Network_failure_is_best_effort_and_logged_without_secrets()
    {
        var logger = new RecordingLogger<RpInitiatedLogout>();
        var logout = CreateLogout(
            new StubHandler((_, _) => throw new HttpRequestException("connection refused")),
            logger);

        await logout.LogoutAsync(PropertiesWithRefreshToken(), CancellationToken.None);

        var entry = Assert.Single(logger.Entries);
        Assert.Equal(LogLevel.Warning, entry.Level);
        Assert.IsType<HttpRequestException>(entry.Exception);
        Assert.Equal("idp_logout_failed", entry.Fields["event"]);
        Assert.Equal("failure", entry.Fields["outcome"]);
        Assert.Equal("idp_unreachable", entry.Fields["error_code"]);
        Assert.DoesNotContain("refresh-token", LogText(entry), StringComparison.Ordinal);
        Assert.DoesNotContain("client-secret", LogText(entry), StringComparison.Ordinal);
    }

    private static RpInitiatedLogout CreateLogout(
        HttpMessageHandler handler,
        RecordingLogger<RpInitiatedLogout> logger)
    {
        var options = new OpenIdConnectOptions
        {
            ClientId = "stackverse-gateway",
            ClientSecret = "client-secret",
            Backchannel = new HttpClient(handler),
            ConfigurationManager = new StaticConfigurationManager<OpenIdConnectConfiguration>(
                new OpenIdConnectConfiguration { EndSessionEndpoint = "http://idp.invalid/logout" }),
        };
        return new RpInitiatedLogout(new StaticOptionsMonitor(options), logger);
    }

    private static AuthenticationProperties PropertiesWithRefreshToken()
    {
        var properties = new AuthenticationProperties();
        properties.StoreTokens(
        [
            new AuthenticationToken { Name = "refresh_token", Value = "refresh-token" },
        ]);
        return properties;
    }

    private static string LogText(RecordedLogEntry entry) => string.Join(
        "|",
        new[] { entry.Message, entry.Exception?.ToString() ?? "" }
            .Concat(entry.Fields.Select(field => $"{field.Key}={field.Value}")));

    private sealed class StubHandler(
        Func<HttpRequestMessage, CancellationToken, HttpResponseMessage> respond) : HttpMessageHandler
    {
        public int CallCount { get; private set; }

        protected override Task<HttpResponseMessage> SendAsync(
            HttpRequestMessage request,
            CancellationToken cancellationToken)
        {
            CallCount++;
            return Task.FromResult(respond(request, cancellationToken));
        }
    }

    private sealed class StaticOptionsMonitor(OpenIdConnectOptions options) : IOptionsMonitor<OpenIdConnectOptions>
    {
        public OpenIdConnectOptions CurrentValue => options;

        public OpenIdConnectOptions Get(string? name) => options;

        public IDisposable? OnChange(Action<OpenIdConnectOptions, string?> listener) => null;
    }
}
