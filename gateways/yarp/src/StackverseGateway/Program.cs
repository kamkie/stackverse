using System.Net.Http.Headers;
using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Authentication.OpenIdConnect;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using StackExchange.Redis;
using StackverseGateway;
using Yarp.ReverseProxy.Configuration;
using Yarp.ReverseProxy.Transforms;

var builder = WebApplication.CreateBuilder(new WebApplicationOptions
{
    Args = args,
    // SPA_ROOT points the static file root somewhere other than the bundled wwwroot
    // (e.g. a frontend production build); FRONTEND_URL takes precedence over both.
    WebRootPath = Environment.GetEnvironmentVariable("SPA_ROOT"),
});

var gateway = GatewayOptions.Load(builder.Configuration);
builder.WebHost.UseUrls($"http://*:{gateway.Port}");

// --- Redis: session tickets, and Data Protection keys so any instance can decrypt
// --- the session cookie and OIDC state. Both are required for statelessness.
var redisConfig = ConfigurationOptions.Parse(gateway.RedisConfiguration);
redisConfig.AbortOnConnectFail = false; // keep retrying while Redis comes up
var redis = await ConnectionMultiplexer.ConnectAsync(redisConfig);
builder.Services.AddSingleton<IConnectionMultiplexer>(redis);
builder.Services.AddStackExchangeRedisCache(options =>
    options.ConnectionMultiplexerFactory = () => Task.FromResult<IConnectionMultiplexer>(redis));
builder.Services.AddDataProtection()
    .SetApplicationName("stackverse-gateway")
    .PersistKeysToStackExchangeRedis(redis, "stackverse:dataprotection-keys");

// --- Authentication: cookie session (tickets in Redis) + OIDC code flow with PKCE.
builder.Services.AddSingleton<RedisTicketStore>();
builder.Services.AddSingleton<AccessTokenManager>();
builder.Services.AddSingleton<RpInitiatedLogout>();
builder.Services.AddOptions<CookieAuthenticationOptions>(CookieAuthenticationDefaults.AuthenticationScheme)
    .Configure<RedisTicketStore>((options, store) => options.SessionStore = store);

var cookieSecurePolicy = gateway.CookiesSecure ? CookieSecurePolicy.Always : CookieSecurePolicy.SameAsRequest;

builder.Services.AddAuthentication(options =>
    {
        options.DefaultScheme = CookieAuthenticationDefaults.AuthenticationScheme;
        options.DefaultChallengeScheme = OpenIdConnectDefaults.AuthenticationScheme;
    })
    .AddCookie(options =>
    {
        options.Cookie.Name = "stackverse_session";
        options.Cookie.HttpOnly = true;
        options.Cookie.SameSite = SameSiteMode.Lax;
        options.Cookie.SecurePolicy = cookieSecurePolicy;
        options.ExpireTimeSpan = TimeSpan.FromHours(8);
        options.SlidingExpiration = true;
        options.LoginPath = "/auth/login";
    })
    .AddOpenIdConnect(options =>
    {
        options.Authority = gateway.OidcIssuerUri;
        options.RequireHttpsMetadata = gateway.OidcIssuerUri.StartsWith("https", StringComparison.OrdinalIgnoreCase);
        options.ClientId = gateway.OidcClientId;
        options.ClientSecret = gateway.OidcClientSecret;
        options.ResponseType = OpenIdConnectResponseType.Code; // + PKCE, on by default for code flow
        options.ResponseMode = OpenIdConnectResponseMode.Query; // contract: GET /auth/callback?code=...
        // .NET auto-enables PAR when the IdP advertises it; stick to the plain
        // front-channel authorization request all gateway stacks share.
        options.PushedAuthorizationBehavior = PushedAuthorizationBehavior.Disable;
        options.CallbackPath = "/auth/callback";
        options.SaveTokens = true; // tokens live in the ticket, i.e. in Redis
        options.Scope.Add("email");
        options.MapInboundClaims = false;
        options.TokenValidationParameters.NameClaimType = "preferred_username";
        // The callback is a top-level GET navigation, so Lax correlation/nonce cookies work.
        options.NonceCookie.SameSite = SameSiteMode.Lax;
        options.NonceCookie.SecurePolicy = cookieSecurePolicy;
        options.CorrelationCookie.SameSite = SameSiteMode.Lax;
        options.CorrelationCookie.SecurePolicy = cookieSecurePolicy;
        options.Events = new OpenIdConnectEvents
        {
            // Build redirect_uri from PUBLIC_URL, not from whatever Host header the
            // request carried — deterministic, and matches the registered client.
            OnRedirectToIdentityProvider = context =>
            {
                context.ProtocolMessage.RedirectUri = new Uri(gateway.PublicUrl, "/auth/callback").ToString();
                return Task.CompletedTask;
            },
        };
    });

// --- YARP: /api/** → backend with token relay; /** → frontend dev server when configured.
var routes = new List<RouteConfig>
{
    new()
    {
        RouteId = "api",
        ClusterId = "backend",
        Match = new RouteMatch { Path = "/api/{**rest}" },
    },
};
var clusters = new List<ClusterConfig>
{
    new()
    {
        ClusterId = "backend",
        Destinations = new Dictionary<string, DestinationConfig>
        {
            ["backend"] = new() { Address = gateway.BackendUrl.ToString() },
        },
    },
};
if (gateway.FrontendUrl is not null)
{
    routes.Add(new RouteConfig
    {
        RouteId = "frontend",
        ClusterId = "frontend",
        Order = 100, // behind /api and the literal /auth endpoints
        Match = new RouteMatch { Path = "/{**rest}" },
    });
    clusters.Add(new ClusterConfig
    {
        ClusterId = "frontend",
        Destinations = new Dictionary<string, DestinationConfig>
        {
            ["frontend"] = new() { Address = gateway.FrontendUrl.ToString() },
        },
    });
}

builder.Services.AddReverseProxy()
    .LoadFromMemory(routes, clusters)
    .AddTransforms(context =>
    {
        if (context.Route.RouteId == "api")
        {
            context.AddRequestTransform(transform =>
            {
                // Placed by the /api guard middleware below.
                if (transform.HttpContext.Items["gw:access-token"] is string accessToken)
                {
                    transform.ProxyRequest.Headers.Authorization =
                        new AuthenticationHeaderValue("Bearer", accessToken);
                }
                return ValueTask.CompletedTask;
            });
        }
    });

var app = builder.Build();

app.UseAuthentication();

// Every browser gets the readable CSRF token cookie (see docs/ARCHITECTURE.md).
app.Use((context, next) =>
{
    Csrf.IssueToken(context, gateway.CookiesSecure);
    return next(context);
});

// Guard /api/** before the proxy: no session → 401 problem (never a redirect),
// CSRF mismatch on state-changing methods → 403 problem, then a fresh access token.
app.UseWhen(
    context => context.Request.Path.StartsWithSegments("/api"),
    api => api.Use(async (context, next) =>
    {
        var auth = await context.AuthenticateAsync();
        if (!auth.Succeeded)
        {
            await Problems.Write(context, StatusCodes.Status401Unauthorized,
                "Unauthorized", "No active session. Log in via /auth/login.");
            return;
        }
        if (!Csrf.IsValid(context.Request))
        {
            await Problems.Write(context, StatusCodes.Status403Forbidden,
                "Forbidden", $"Missing or mismatched {Csrf.HeaderName} header.");
            return;
        }
        var accessToken = await context.RequestServices
            .GetRequiredService<AccessTokenManager>()
            .GetAccessTokenAsync(auth, context.RequestAborted);
        if (accessToken is null)
        {
            // The session can no longer produce a token; destroy it so the SPA sees a clean logged-out state.
            await context.SignOutAsync(CookieAuthenticationDefaults.AuthenticationScheme);
            await Problems.Write(context, StatusCodes.Status401Unauthorized,
                "Unauthorized", "Session expired. Log in again via /auth/login.");
            return;
        }
        context.Items["gw:access-token"] = accessToken;
        await next();
    }));

// --- The /auth surface (the OIDC handler itself serves GET /auth/callback).
app.MapGet("/auth/login", () => Results.Challenge(
    new AuthenticationProperties { RedirectUri = "/" },
    [OpenIdConnectDefaults.AuthenticationScheme]));

app.MapGet("/auth/session", (ClaimsPrincipal user) =>
    user.Identity?.IsAuthenticated == true
        ? Results.Json((object)new { authenticated = true, username = user.Identity.Name })
        : Results.Json((object)new { authenticated = false }));

app.MapPost("/auth/logout", async (HttpContext context, RpInitiatedLogout idpLogout) =>
{
    var auth = await context.AuthenticateAsync();
    if (auth.Succeeded)
    {
        await idpLogout.LogoutAsync(auth.Properties!, context.RequestAborted);
        await context.SignOutAsync(CookieAuthenticationDefaults.AuthenticationScheme);
    }
    return Results.NoContent();
});

// --- SPA delivery: proxy the dev server when FRONTEND_URL is set, otherwise serve
// --- static files (SPA_ROOT or the bundled wwwroot) with a fallback to index.html.
if (gateway.FrontendUrl is null)
{
    app.UseStaticFiles();
    app.MapFallbackToFile("index.html");
}

app.MapReverseProxy();

app.Run();

public partial class Program; // exposes the entry point to WebApplicationFactory
