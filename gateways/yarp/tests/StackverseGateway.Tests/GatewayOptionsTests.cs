using Microsoft.Extensions.Configuration;

namespace StackverseGateway.Tests;

public sealed class GatewayOptionsTests
{
    [Fact]
    public void Defaults_match_the_shared_gateway_contract()
    {
        var options = GatewayOptions.Load(new ConfigurationBuilder().Build());

        Assert.Equal(8000, options.Port);
        Assert.Equal(new Uri("http://localhost:8080"), options.BackendUrl);
        Assert.Null(options.FrontendUrl);
        Assert.Equal("localhost:6379", options.RedisConfiguration);
        Assert.Equal("http://localhost:8180/realms/stackverse", options.OidcIssuerUri);
        Assert.Equal("stackverse-gateway", options.OidcClientId);
        Assert.Equal("stackverse-secret", options.OidcClientSecret);
        Assert.Equal(new Uri("http://localhost:8000"), options.PublicUrl);
        Assert.False(options.CookiesSecure);
    }

    [Fact]
    public void Environment_values_are_parsed_and_normalized()
    {
        var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["PORT"] = "9000",
            ["BACKEND_URL"] = "https://backend.example/base/",
            ["FRONTEND_URL"] = "https://frontend.example/",
            ["REDIS_URL"] = "rediss://session-secret@redis.example:6380/4",
            ["OIDC_ISSUER_URI"] = "https://idp.example/realms/stackverse/",
            ["OIDC_CLIENT_ID"] = "custom-client",
            ["OIDC_CLIENT_SECRET"] = "custom-secret",
            ["PUBLIC_URL"] = "https://stackverse.example/",
        }).Build();

        var options = GatewayOptions.Load(config);

        Assert.Equal(9000, options.Port);
        Assert.Equal(new Uri("https://backend.example/base/"), options.BackendUrl);
        Assert.Equal(new Uri("https://frontend.example/"), options.FrontendUrl);
        Assert.Equal(
            "redis.example:6380,ssl=true,password=session-secret,defaultDatabase=4",
            options.RedisConfiguration);
        Assert.Equal("https://idp.example/realms/stackverse", options.OidcIssuerUri);
        Assert.Equal("custom-client", options.OidcClientId);
        Assert.Equal("custom-secret", options.OidcClientSecret);
        Assert.Equal(new Uri("https://stackverse.example/"), options.PublicUrl);
        Assert.True(options.CookiesSecure);
    }

    [Theory]
    [InlineData(null)]
    [InlineData("")]
    [InlineData("   ")]
    public void Missing_or_blank_frontend_url_selects_the_static_spa_fallback(string? frontendUrl)
    {
        var config = new ConfigurationBuilder().AddInMemoryCollection(new Dictionary<string, string?>
        {
            ["FRONTEND_URL"] = frontendUrl,
        }).Build();

        Assert.Null(GatewayOptions.Load(config).FrontendUrl);
    }
}
