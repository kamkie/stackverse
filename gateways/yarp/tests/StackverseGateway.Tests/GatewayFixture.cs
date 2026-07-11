using Microsoft.AspNetCore.Hosting;
using Microsoft.AspNetCore.Mvc.Testing;
using Testcontainers.Keycloak;
using Testcontainers.Redis;

namespace StackverseGateway.Tests;

/// <summary>
/// Boots the real dependency set once per test class: Keycloak with the shared
/// stackverse realm imported from infra/keycloak, Redis for sessions, and a stub
/// backend — then hosts the gateway with WebApplicationFactory on top of them.
/// </summary>
public sealed class GatewayFixture : IAsyncLifetime
{
    private readonly RedisContainer _redis = new RedisBuilder("redis:8.8.0-alpine")
        .Build();

    private readonly KeycloakContainer _keycloak = new KeycloakBuilder("quay.io/keycloak/keycloak:26.7.0")
        .WithResourceMapping(new FileInfo(FindRealmFile()), "/opt/keycloak/data/import/")
        .WithCommand("--import-realm")
        .Build();

    public StubBackend Backend { get; } = new();
    public StubFrontend Frontend { get; } = new();
    public WebApplicationFactory<Program> Factory { get; private set; } = null!;
    public string KeycloakBaseUrl => _keycloak.GetBaseAddress().TrimEnd('/');

    /// <summary>
    /// Simulates an IdP outage by stopping the Keycloak container. Fixtures are
    /// class-scoped, so only the test class that calls this sees the dead IdP.
    /// </summary>
    public Task StopKeycloakAsync() => _keycloak.StopAsync();

    public async Task InitializeAsync()
    {
        await Task.WhenAll(_redis.StartAsync(), _keycloak.StartAsync(), Backend.StartAsync(), Frontend.StartAsync());

        Factory = new WebApplicationFactory<Program>().WithWebHostBuilder(builder =>
        {
            builder.UseSetting("BACKEND_URL", Backend.Url);
            builder.UseSetting("FRONTEND_URL", Frontend.Url);
            builder.UseSetting("REDIS_URL", "redis://" + _redis.GetConnectionString());
            builder.UseSetting("OIDC_ISSUER_URI", KeycloakBaseUrl + "/realms/stackverse");
        });
    }

    public async Task DisposeAsync()
    {
        await Factory.DisposeAsync();
        await Frontend.DisposeAsync();
        await Backend.DisposeAsync();
        await Task.WhenAll(_redis.DisposeAsync().AsTask(), _keycloak.DisposeAsync().AsTask());
    }

    /// <summary>The realm definition is the shared one in infra/keycloak — walk up to the repo root.</summary>
    private static string FindRealmFile()
    {
        for (var dir = new DirectoryInfo(AppContext.BaseDirectory); dir is not null; dir = dir.Parent)
        {
            var candidate = Path.Combine(dir.FullName, "infra", "keycloak", "stackverse-realm.json");
            if (File.Exists(candidate))
            {
                return candidate;
            }
        }
        throw new FileNotFoundException("infra/keycloak/stackverse-realm.json not found in any parent directory");
    }
}
