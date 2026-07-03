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
                if (options.OidcJwksUri is { } jwksUri)
                {
                    // inside compose the issuer host is not dialable from a container;
                    // keys are then fetched here while the `iss` validation stays as-is
                    bearer.ConfigurationManager = new ConfigurationManager<OpenIdConnectConfiguration>(
                        jwksUri,
                        new JwksRetriever(),
                        new HttpDocumentRetriever { RequireHttps = jwksUri.StartsWith("https", StringComparison.OrdinalIgnoreCase) });
                }
                else
                {
                    bearer.Authority = options.OidcIssuerUri;
                    bearer.RequireHttpsMetadata = options.OidcIssuerUri.StartsWith("https", StringComparison.OrdinalIgnoreCase);
                }
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
                    // the JWKS fetch is a dependency call; its failure is an ERROR, not a 401 statistic
                    OnAuthenticationFailed = context =>
                    {
                        if (context.Exception is InvalidOperationException or IOException or HttpRequestException)
                        {
                            context.HttpContext.RequestServices.GetRequiredService<ILoggerFactory>()
                                .CreateLogger("StackverseBackend.Auth")
                                .Event(LogLevel.Error, "dependency_call_failed", "failure",
                                    "Could not obtain the IdP signing keys", context.Exception,
                                    ("dependency", "keycloak-jwks"),
                                    ("error_code", context.Exception.GetType().Name));
                        }
                        return Task.CompletedTask;
                    },
                };
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
