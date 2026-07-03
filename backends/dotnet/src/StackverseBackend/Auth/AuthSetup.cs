using System.Diagnostics;
using System.Security.Claims;
using System.Text.Json;
using Microsoft.AspNetCore.Authentication.JwtBearer;
using Microsoft.AspNetCore.Authorization;
using Microsoft.AspNetCore.Authorization.Policy;
using Microsoft.IdentityModel.Protocols;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using Microsoft.IdentityModel.Tokens;
using StackverseBackend.Common;

namespace StackverseBackend.Auth;

/// <summary>
/// Stateless resource server: every request stands on its own bearer JWT, validated
/// against the IdP's JWKS (issuer + audience checked). Role checks live on the
/// endpoints as authorization policies — each endpoint asks for the single role it
/// needs; the admin ⊃ moderator hierarchy is Keycloak's composite role, never
/// re-implemented here.
/// </summary>
public static class AuthSetup
{
    public static void Configure(WebApplicationBuilder builder, BackendOptions options)
    {
        builder.Services.AddAuthentication(JwtBearerDefaults.AuthenticationScheme)
            .AddJwtBearer(bearer =>
            {
                bearer.MapInboundClaims = false;
                bearer.TokenValidationParameters = new TokenValidationParameters
                {
                    ValidIssuer = options.OidcIssuerUri,
                    ValidAudience = BackendOptions.Audience,
                    NameClaimType = "preferred_username",
                    RoleClaimType = ClaimTypes.Role,
                };
                bearer.Events = new JwtBearerEvents
                {
                    // identity = `preferred_username`; roles = `realm_access.roles` (SPEC rule 6)
                    OnTokenValidated = context =>
                    {
                        if (context.Principal?.Identity is ClaimsIdentity identity
                            && context.Principal.FindFirstValue("realm_access") is { } realmAccess)
                        {
                            foreach (var role in ParseRealmRoles(realmAccess))
                            {
                                identity.AddClaim(new Claim(ClaimTypes.Role, role));
                            }
                        }
                        return Task.CompletedTask;
                    },
                    // a missing token on an endpoint that requires one; a *rejected* token
                    // is turned into its 401 by the UserAccountMiddleware instead
                    OnChallenge = context =>
                    {
                        context.HandleResponse();
                        return Problems.Write(context.HttpContext, StatusCodes.Status401Unauthorized,
                            "Unauthorized", "Authentication is required.");
                    },
                };
            });

        // Signing keys come from OIDC_JWKS_URI when the issuer host is not directly
        // dialable (compose) — the `iss` validation stays as-is — and from the
        // issuer's OIDC discovery otherwise. Both fetches go through the same
        // instrumented retriever, so an unreachable IdP logs dependency_call_failed
        // with a real duration (docs/LOGGING.md §5). Configured via DI options
        // because the retriever needs a logger.
        builder.Services.AddOptions<JwtBearerOptions>(JwtBearerDefaults.AuthenticationScheme)
            .Configure<ILoggerFactory>((bearer, loggerFactory) =>
            {
                var address = options.OidcJwksUri ?? $"{options.OidcIssuerUri}/.well-known/openid-configuration";
                var retriever = new InstrumentedDocumentRetriever(
                    loggerFactory.CreateLogger("StackverseBackend.Auth"),
                    requireHttps: address.StartsWith("https", StringComparison.OrdinalIgnoreCase));
                bearer.ConfigurationManager = options.OidcJwksUri is null
                    ? new ConfigurationManager<OpenIdConnectConfiguration>(
                        address, new OpenIdConnectConfigurationRetriever(), retriever)
                    : new ConfigurationManager<OpenIdConnectConfiguration>(
                        address, new JwksRetriever(), retriever);
            });

        builder.Services.AddAuthorizationBuilder()
            // every endpoint requires an authenticated caller unless it opts out
            // with AllowAnonymous (the public surface, SPEC rules 2 + 7)
            .SetFallbackPolicy(new AuthorizationPolicyBuilder().RequireAuthenticatedUser().Build())
            .AddPolicy("moderator", policy => policy.RequireRole("moderator"))
            .AddPolicy("admin", policy => policy.RequireRole("admin"));

        builder.Services.AddSingleton<IAuthorizationMiddlewareResultHandler, ProblemAuthorizationResultHandler>();
    }

    internal static IEnumerable<string> ParseRealmRoles(string realmAccessJson)
    {
        using var document = JsonDocument.Parse(realmAccessJson);
        if (document.RootElement.ValueKind == JsonValueKind.Object
            && document.RootElement.TryGetProperty("roles", out var roles)
            && roles.ValueKind == JsonValueKind.Array)
        {
            return roles.EnumerateArray()
                .Where(role => role.ValueKind == JsonValueKind.String)
                .Select(role => role.GetString()!)
                .ToList();
        }
        return [];
    }

    /// <summary>
    /// Fetches signing keys straight from a JWKS document (no OIDC discovery),
    /// for deployments where only the JWKS endpoint is dialable.
    /// </summary>
    private sealed class JwksRetriever : IConfigurationRetriever<OpenIdConnectConfiguration>
    {
        public async Task<OpenIdConnectConfiguration> GetConfigurationAsync(
            string address, IDocumentRetriever retriever, CancellationToken cancel)
        {
            var document = await retriever.GetDocumentAsync(address, cancel);
            var configuration = new OpenIdConnectConfiguration { JwksUri = address, JsonWebKeySet = new JsonWebKeySet(document) };
            foreach (var key in configuration.JsonWebKeySet.GetSigningKeys())
            {
                configuration.SigningKeys.Add(key);
            }
            return configuration;
        }
    }

    /// <summary>
    /// The metadata/JWKS fetch is a dependency call: a failure logs
    /// `dependency_call_failed` with the elapsed time, then surfaces as the usual
    /// authentication failure (an expected 401 for the caller, docs/LOGGING.md §3).
    /// </summary>
    private sealed class InstrumentedDocumentRetriever(ILogger logger, bool requireHttps) : IDocumentRetriever
    {
        private readonly HttpDocumentRetriever _inner = new() { RequireHttps = requireHttps };

        public async Task<string> GetDocumentAsync(string address, CancellationToken cancel)
        {
            var start = Stopwatch.GetTimestamp();
            try
            {
                return await _inner.GetDocumentAsync(address, cancel);
            }
            catch (Exception exception)
            {
                logger.Event(LogLevel.Error, "dependency_call_failed", "failure",
                    "Could not fetch the IdP metadata / signing keys", exception,
                    ("dependency", "keycloak-jwks"),
                    ("duration_ms", (long)Stopwatch.GetElapsedTime(start).TotalMilliseconds),
                    ("error_code", exception.GetType().Name));
                throw;
            }
        }
    }
}

/// <summary>
/// Renders authorization failures as RFC 9457 problem documents: a valid token
/// without the required role is an expected 403 — a security signal logged at
/// INFO (docs/LOGGING.md §3), never an error.
/// </summary>
public sealed class ProblemAuthorizationResultHandler(ILogger<ProblemAuthorizationResultHandler> logger)
    : IAuthorizationMiddlewareResultHandler
{
    private readonly AuthorizationMiddlewareResultHandler _defaultHandler = new();

    public Task HandleAsync(RequestDelegate next, HttpContext context, AuthorizationPolicy policy, PolicyAuthorizationResult authorizeResult)
    {
        if (authorizeResult.Forbidden)
        {
            logger.Event(LogLevel.Information, "authz_denied", "denied",
                "Denied a request lacking the required role",
                fields: [("actor", context.User.Identity?.Name)]);
            return Problems.Write(context, StatusCodes.Status403Forbidden, "Forbidden",
                "You do not have the role required for this operation.");
        }
        return _defaultHandler.HandleAsync(next, context, policy, authorizeResult);
    }
}
