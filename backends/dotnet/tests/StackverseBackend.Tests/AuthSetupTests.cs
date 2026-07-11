using System.Security.Claims;
using System.Text;
using System.Text.Json;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Builder;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Options;
using StackverseBackend.Auth;

namespace StackverseBackend.Tests;

public class AuthSetupTests
{
    [Fact]
    public void Realm_roles_extract_only_string_entries_in_wire_order()
    {
        var roles = AuthSetup.ParseRealmRoles("{\"roles\":[\"moderator\",42,null,\"admin\"]}");

        Assert.Equal(["moderator", "admin"], roles);
    }

    [Theory]
    [InlineData("{}")]
    [InlineData("{\"roles\":\"admin\"}")]
    [InlineData("[]")]
    public void Missing_or_nonarray_roles_are_empty(string realmAccess)
    {
        Assert.Empty(AuthSetup.ParseRealmRoles(realmAccess));
    }

    [Fact]
    public async Task Jwt_bearer_boundary_pins_contract_claims_roles_and_problem_challenge()
    {
        var builder = WebApplication.CreateBuilder(new WebApplicationOptions
        {
            EnvironmentName = "Testing",
        });
        AuthSetup.Configure(builder, Options());
        await using var app = builder.Build();
        var bearer = app.Services.GetRequiredService<IOptionsMonitor<JwtBearerOptions>>()
            .Get(JwtBearerDefaults.AuthenticationScheme);
        var scheme = new AuthenticationScheme(
            JwtBearerDefaults.AuthenticationScheme,
            displayName: null,
            typeof(JwtBearerHandler));

        Assert.False(bearer.MapInboundClaims);
        Assert.Equal("https://issuer.example/realms/stackverse", bearer.TokenValidationParameters.ValidIssuer);
        Assert.Equal(BackendOptions.Audience, bearer.TokenValidationParameters.ValidAudience);
        Assert.Equal("preferred_username", bearer.TokenValidationParameters.NameClaimType);
        Assert.Equal(ClaimTypes.Role, bearer.TokenValidationParameters.RoleClaimType);
        Assert.NotNull(bearer.ConfigurationManager);

        var identity = new ClaimsIdentity(
            [
                new Claim("preferred_username", "admin"),
                new Claim("realm_access", "{\"roles\":[\"moderator\",\"admin\"]}"),
            ],
            JwtBearerDefaults.AuthenticationScheme,
            "preferred_username",
            ClaimTypes.Role);
        var tokenContext = new TokenValidatedContext(new DefaultHttpContext(), scheme, bearer)
        {
            Principal = new ClaimsPrincipal(identity),
        };

        await bearer.Events.OnTokenValidated(tokenContext);

        Assert.True(tokenContext.Principal!.IsInRole("moderator"));
        Assert.True(tokenContext.Principal.IsInRole("admin"));

        var http = new DefaultHttpContext();
        http.Response.Body = new MemoryStream();
        var challenge = new JwtBearerChallengeContext(
            http,
            scheme,
            bearer,
            new AuthenticationProperties());

        await bearer.Events.OnChallenge(challenge);

        Assert.True(challenge.Handled);
        Assert.Equal(StatusCodes.Status401Unauthorized, http.Response.StatusCode);
        Assert.Equal("application/problem+json", http.Response.ContentType);
        http.Response.Body.Position = 0;
        using var reader = new StreamReader(http.Response.Body, Encoding.UTF8, leaveOpen: true);
        var problem = JsonDocument.Parse(await reader.ReadToEndAsync()).RootElement;
        Assert.Equal("Unauthorized", problem.GetProperty("title").GetString());
        Assert.Equal("Authentication is required.", problem.GetProperty("detail").GetString());
    }

    private static BackendOptions Options() => new()
    {
        Port = 8080,
        DbHost = "localhost",
        DbPort = 5432,
        DbName = "stackverse",
        DbUser = "stackverse",
        DbPassword = "stackverse",
        OidcIssuerUri = "https://issuer.example/realms/stackverse",
        OidcJwksUri = "https://issuer.example/realms/stackverse/protocol/openid-connect/certs",
        SeedMessagesDir = "seed",
    };
}
