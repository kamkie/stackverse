using System.Text;

namespace StackverseGateway;

/// <summary>
/// Environment-driven configuration — one property per variable in gateways/README.md.
/// SPA_ROOT is consumed directly as WebRootPath in Program.cs.
/// </summary>
public sealed record GatewayOptions
{
    public required int Port { get; init; }
    public required Uri BackendUrl { get; init; }
    public Uri? FrontendUrl { get; init; }
    public required string RedisConfiguration { get; init; }
    public required string OidcIssuerUri { get; init; }
    public required string OidcClientId { get; init; }
    public required string OidcClientSecret { get; init; }
    public required Uri PublicUrl { get; init; }

    /// <summary>The contract: cookies are Secure outside local dev, i.e. whenever the public URL is https.</summary>
    public bool CookiesSecure => PublicUrl.Scheme == Uri.UriSchemeHttps;

    public static GatewayOptions Load(IConfiguration config) => new()
    {
        Port = int.Parse(config["PORT"] ?? "8000"),
        BackendUrl = new Uri(config["BACKEND_URL"] ?? "http://localhost:8080"),
        FrontendUrl = config["FRONTEND_URL"] is { Length: > 0 } frontend ? new Uri(frontend) : null,
        RedisConfiguration = ParseRedisUrl(config["REDIS_URL"] ?? "redis://localhost:6379"),
        OidcIssuerUri = (config["OIDC_ISSUER_URI"] ?? "http://localhost:8180/realms/stackverse").TrimEnd('/'),
        OidcClientId = config["OIDC_CLIENT_ID"] ?? "stackverse-gateway",
        OidcClientSecret = config["OIDC_CLIENT_SECRET"] ?? "stackverse-secret",
        PublicUrl = new Uri(config["PUBLIC_URL"] ?? "http://localhost:8000"),
    };

    /// <summary>
    /// StackExchange.Redis takes "host:port,option=value" strings, not redis:// URLs;
    /// translate the conventional REDIS_URL (redis[s]://[user:password@]host[:port][/db]).
    /// </summary>
    internal static string ParseRedisUrl(string url)
    {
        if (!url.Contains("://"))
        {
            return url; // already a StackExchange.Redis configuration string
        }

        var uri = new Uri(url);
        var config = new StringBuilder($"{uri.Host}:{(uri.Port > 0 ? uri.Port : 6379)}");
        if (uri.Scheme == "rediss")
        {
            config.Append(",ssl=true");
        }
        if (uri.UserInfo is { Length: > 0 } userInfo)
        {
            var parts = userInfo.Split(':', 2);
            if (parts.Length == 2)
            {
                if (parts[0].Length > 0)
                {
                    config.Append($",user={Uri.UnescapeDataString(parts[0])}");
                }
                config.Append($",password={Uri.UnescapeDataString(parts[1])}");
            }
            else
            {
                config.Append($",password={Uri.UnescapeDataString(parts[0])}");
            }
        }
        if (uri.AbsolutePath.TrimStart('/') is { Length: > 0 } database)
        {
            config.Append($",defaultDatabase={database}");
        }
        return config.ToString();
    }
}
