using System.Net.Http.Headers;
using System.Security.Claims;
using Microsoft.AspNetCore.Authentication;
using Microsoft.AspNetCore.Authentication.Cookies;
using Microsoft.AspNetCore.Authentication.OpenIdConnect;
using Microsoft.AspNetCore.DataProtection;
using Microsoft.IdentityModel.Protocols.OpenIdConnect;
using OpenTelemetry.Logs;
using OpenTelemetry.Metrics;
using OpenTelemetry.Trace;
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

// --- Console logging: LOG_LEVEL and LOG_FORMAT per docs/LOGGING.md §8.
LoggingSetup.Configure(builder);

// --- Observability: OTLP export of traces, metrics, and logs. Endpoint,
// --- protocol, and service name come from the standard OTEL_* env vars
// --- (see gateways/README.md). Export is opt-in: the documented default for
// --- OTEL_SDK_DISABLED is `true`, so it takes an explicit `false` (plus a
// --- configured endpoint) to turn the pipeline on.
var otlpEndpointConfigured =
    !string.IsNullOrEmpty(Environment.GetEnvironmentVariable("OTEL_EXPORTER_OTLP_ENDPOINT"));
var otelExportEnabled = string.Equals(
    Environment.GetEnvironmentVariable("OTEL_SDK_DISABLED"), "false", StringComparison.OrdinalIgnoreCase);
if (otlpEndpointConfigured && otelExportEnabled)
{
    builder.Services.AddOpenTelemetry()
        .WithTracing(tracing => tracing
            .AddAspNetCoreInstrumentation()
            .AddHttpClientInstrumentation()
            .AddOtlpExporter())
        .WithMetrics(metrics => metrics
            .AddAspNetCoreInstrumentation()
            .AddHttpClientInstrumentation()
            .AddOtlpExporter())
        .WithLogging(logging => logging.AddOtlpExporter());
}

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
        options.Events = new CookieAuthenticationEvents
        {
            OnSignedIn = context =>
            {
                context.HttpContext.RequestServices.GetRequiredService<ILoggerFactory>()
                    .CreateLogger("StackverseGateway.Session")
                    .Event(LogLevel.Information, "session_created", "success",
                        "Session ticket stored in Redis, cookie issued",
                        fields: [("actor", context.Principal?.Identity?.Name)]);
                return Task.CompletedTask;
            },
        };
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
            OnTokenValidated = context =>
            {
                context.HttpContext.RequestServices.GetRequiredService<ILoggerFactory>()
                    .CreateLogger("StackverseGateway.Oidc")
                    .Event(LogLevel.Information, "oidc_callback_completed", "success",
                        "Authorization code flow completed",
                        fields: [("actor", context.Principal?.Identity?.Name)]);
                return Task.CompletedTask;
            },
            // an expected client/IdP signal, not an application error — INFO, and only
            // the failure type: the message could echo client-controlled query values
            OnRemoteFailure = context =>
            {
                context.HttpContext.RequestServices.GetRequiredService<ILoggerFactory>()
                    .CreateLogger("StackverseGateway.Oidc")
                    .Event(LogLevel.Information, "oidc_callback_completed", "failure",
                        "Authorization code flow failed",
                        fields: [("error_code", context.Failure?.GetType().Name ?? "remote_failure")]);
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
        // The browser's cookies (session key, CSRF token) are gateway-only state;
        // nothing upstream may see them — the session lives at the edge.
        context.AddRequestHeaderRemove("Cookie");
        if (context.Route.RouteId == "api")
        {
            // Validated at the gateway; not part of the API semantics.
            context.AddRequestHeaderRemove(Csrf.HeaderName);
            // The gateway session is the only source of upstream identity — a
            // client-supplied Authorization header must never reach the backend.
            context.AddRequestHeaderRemove("Authorization");
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

// Guard /api/** before the proxy: CSRF on state-changing methods (403 problem),
// then a fresh access token when a session exists. Anonymous requests relay
// without a token — the spec's public surface must work logged-out, and which
// endpoints require auth is the backend's decision, not the gateway's.
app.UseWhen(
    context => context.Request.Path.StartsWithSegments("/api"),
    api => api.Use(async (context, next) =>
    {
        if (!Csrf.IsValid(context.Request))
        {
            // expected client behavior and a security signal — never above INFO (docs/LOGGING.md §3)
            app.Logger.Event(LogLevel.Information, "csrf_validation_failed", "denied",
                "Rejected a state-changing /api request without a matching CSRF header",
                fields: [("method", context.Request.Method), ("path", context.Request.Path.Value)]);
            await Problems.Write(context, StatusCodes.Status403Forbidden,
                "Forbidden", $"Missing or mismatched {Csrf.HeaderName} header.");
            return;
        }
        var auth = await context.AuthenticateAsync();
        if (auth.Succeeded)
        {
            var accessToken = await context.RequestServices
                .GetRequiredService<AccessTokenManager>()
                .GetAccessTokenAsync(auth, context.RequestAborted);
            if (accessToken is null)
            {
                // The session can no longer produce a token: destroy it and degrade to
                // anonymous. The SPA notices via the backend's 401 or /auth/session.
                await context.SignOutAsync(CookieAuthenticationDefaults.AuthenticationScheme);
                app.Logger.Event(LogLevel.Information, "session_destroyed", "success",
                    "Session destroyed after a failed token refresh; request degraded to anonymous",
                    fields: [("reason", "token_refresh_failed"), ("actor", auth.Principal?.Identity?.Name)]);
            }
            else
            {
                context.Items["gw:access-token"] = accessToken;
            }
        }
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
        // Local session first: logout must not depend on the IdP being reachable
        // or the client staying connected. The IdP revocation is best effort and
        // deliberately ignores the request abort — the user's intent is recorded.
        await context.SignOutAsync(CookieAuthenticationDefaults.AuthenticationScheme);
        app.Logger.Event(LogLevel.Information, "session_destroyed", "success",
            "Session destroyed by user logout",
            fields: [("reason", "logout"), ("actor", auth.Principal?.Identity?.Name)]);
        await idpLogout.LogoutAsync(auth.Properties!, CancellationToken.None);
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

// --- Lifecycle contract events (docs/LOGGING.md §5): `application_start` with the
// --- effective configuration (secrets excluded), `application_stop` on orderly
// --- shutdown so restarts stay distinguishable from crashes.
app.Lifetime.ApplicationStarted.Register(() =>
    app.Logger.Event(LogLevel.Information, "application_start", "success",
        "Stackverse gateway is up and accepting requests",
        fields:
        [
            ("port", gateway.Port),
            ("backend_url", gateway.BackendUrl),
            ("frontend_url", gateway.FrontendUrl),
            ("public_url", gateway.PublicUrl),
            // host:port only — a configured Redis password lives behind the first comma
            ("redis_endpoint", gateway.RedisConfiguration.Split(',')[0]),
            ("oidc_issuer_uri", gateway.OidcIssuerUri),
            ("oidc_client_id", gateway.OidcClientId),
            ("log_level", builder.Configuration["LOG_LEVEL"] ?? "info"),
            ("log_format", builder.Configuration["LOG_FORMAT"] ?? "json"),
            ("otel_sdk_disabled", !(otlpEndpointConfigured && otelExportEnabled)),
        ]));
app.Lifetime.ApplicationStopping.Register(() =>
    app.Logger.Event(LogLevel.Information, "application_stop", "success",
        "Stackverse gateway shutting down"));

app.Run();

public partial class Program; // exposes the entry point to WebApplicationFactory
