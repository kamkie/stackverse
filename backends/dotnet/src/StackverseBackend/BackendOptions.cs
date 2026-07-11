using System.Globalization;

namespace StackverseBackend;

/// <summary>
/// Environment-driven configuration — one property per variable in backends/README.md.
/// No config files, no profiles-per-environment: the environment is the configuration.
/// </summary>
public sealed record BackendOptions
{
    /// <summary>Expected `aud` claim; the same fixed audience every backend validates.</summary>
    public const string Audience = "stackverse-api";

    public required int Port { get; init; }
    public required string DbHost { get; init; }
    public required int DbPort { get; init; }
    public required string DbName { get; init; }
    public required string DbUser { get; init; }
    public required string DbPassword { get; init; }

    /// <summary>Expected `iss` claim; also the OIDC discovery endpoint when no JWKS URI is given.</summary>
    public required string OidcIssuerUri { get; init; }

    /// <summary>Where to fetch signing keys when the issuer host is not directly dialable (compose).</summary>
    public string? OidcJwksUri { get; init; }

    /// <summary>Language = filename; the directory lives at the repo root (spec/messages).</summary>
    public required string SeedMessagesDir { get; init; }

    public string ConnectionString =>
        $"Host={DbHost};Port={DbPort};Database={DbName};Username={DbUser};Password={DbPassword}";

    public static BackendOptions Load(IConfiguration config, string contentRootPath) => new()
    {
        Port = int.Parse(config["PORT"] ?? "8080", CultureInfo.InvariantCulture),
        DbHost = config["DB_HOST"] ?? "localhost",
        DbPort = int.Parse(config["DB_PORT"] ?? "5432", CultureInfo.InvariantCulture),
        DbName = config["DB_NAME"] ?? "stackverse",
        DbUser = config["DB_USER"] ?? "stackverse",
        DbPassword = config["DB_PASSWORD"] ?? "stackverse",
        OidcIssuerUri = (config["OIDC_ISSUER_URI"] ?? "http://localhost:8180/realms/stackverse").TrimEnd('/'),
        OidcJwksUri = config["OIDC_JWKS_URI"] is { Length: > 0 } jwks ? jwks : null,
        // defaults to the repo's spec/messages relative to the project directory
        // (the content root under `dotnet run`); the Dockerfile bakes the seed into
        // the image and points here explicitly
        SeedMessagesDir = config["SEED_MESSAGES_DIR"] is { Length: > 0 } dir
            ? dir
            : Path.GetFullPath(Path.Combine(contentRootPath, "..", "..", "..", "..", "spec", "messages")),
    };
}
