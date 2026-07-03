using System.Text.Json;
using System.Text.Json.Serialization;
using Microsoft.EntityFrameworkCore;
using Npgsql;
using OpenTelemetry.Logs;
using OpenTelemetry.Metrics;
using OpenTelemetry.Trace;
using StackverseBackend;
using StackverseBackend.Accounts;
using StackverseBackend.Audit;
using StackverseBackend.Auth;
using StackverseBackend.Bookmarks;
using StackverseBackend.Common;
using StackverseBackend.Data;
using StackverseBackend.Messages;
using StackverseBackend.Moderation;
using StackverseBackend.Stats;
using StackverseBackend.Web;

var builder = WebApplication.CreateBuilder(args);

var options = BackendOptions.Load(builder.Configuration, builder.Environment.ContentRootPath);
builder.WebHost.UseUrls($"http://*:{options.Port}");

// --- Console logging: LOG_LEVEL and LOG_FORMAT per docs/LOGGING.md §8.
LoggingSetup.Configure(builder);

// --- Observability: OTLP export of traces, metrics, and logs. Endpoint,
// --- protocol, and service name come from the standard OTEL_* env vars
// --- (see backends/README.md). Export is opt-in: the documented default for
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
            .AddNpgsql()
            .AddOtlpExporter())
        .WithMetrics(metrics => metrics
            .AddAspNetCoreInstrumentation()
            .AddHttpClientInstrumentation()
            .AddOtlpExporter())
        .WithLogging(logging => logging.AddOtlpExporter());
}

// --- Persistence: EF Core + Npgsql; the schema is owned by this backend's
// --- migrations, applied on startup below (backends/README.md).
builder.Services.AddDbContext<AppDbContext>(db => db.UseNpgsql(options.ConnectionString));

// --- The wire format: camelCase, kebab-case enum strings, nulls omitted.
builder.Services.ConfigureHttpJsonOptions(json =>
{
    json.SerializerOptions.DefaultIgnoreCondition = JsonIgnoreCondition.WhenWritingNull;
    json.SerializerOptions.Converters.Add(
        new JsonStringEnumConverter(JsonNamingPolicy.KebabCaseLower, allowIntegerValues: false));
});

// A malformed body is thrown as BadHttpRequestException instead of a bodyless 400,
// so ApiExceptionMiddleware can render the RFC 9457 problem document.
builder.Services.Configure<RouteHandlerOptions>(routes => routes.ThrowOnBadRequest = true);

AuthSetup.Configure(builder, options);

builder.Services.AddScoped<BookmarkService>();
builder.Services.AddScoped<ModerationService>();
builder.Services.AddScoped<MessageService>();
builder.Services.AddScoped<UserAccountService>();
builder.Services.AddScoped<AuditService>();
builder.Services.AddScoped<LanguageResolver>();
builder.Services.AddScoped<MessageLocalizer>();

var app = builder.Build();

app.UseMiddleware<DeprecationHeadersMiddleware>();
app.UseMiddleware<EtagMiddleware>();
app.UseMiddleware<ApiExceptionMiddleware>();
app.UseAuthentication();
app.UseMiddleware<UserAccountMiddleware>();
app.UseAuthorization();

BookmarkEndpoints.Map(app);
ModerationEndpoints.Map(app);
MessageEndpoints.Map(app);
AdminUserEndpoints.Map(app);
AdminAuditEndpoints.Map(app);
AdminStatsEndpoints.Map(app);
MeEndpoints.Map(app);
MetaEndpoints.Map(app);

// --- Schema migrations and the idempotent message seed run before the service
// --- accepts traffic (SPEC acceptance checklist; docs/LOGGING.md §5 lifecycle).
await using (var scope = app.Services.CreateAsyncScope())
{
    var db = scope.ServiceProvider.GetRequiredService<AppDbContext>();
    var pending = (await db.Database.GetPendingMigrationsAsync()).ToList();
    await db.Database.MigrateAsync();
    foreach (var migration in pending)
    {
        app.Logger.Event(LogLevel.Information, "db_migration_applied", "success",
            $"Applied database migration {migration}",
            fields: [("migration", migration)]);
    }
    await MessageSeeder.SeedAsync(db, options.SeedMessagesDir, app.Logger);
}

// --- Lifecycle contract events (docs/LOGGING.md §5): `application_start` with the
// --- effective configuration (secrets excluded), `application_stop` on orderly
// --- shutdown so restarts stay distinguishable from crashes.
app.Lifetime.ApplicationStarted.Register(() =>
    app.Logger.Event(LogLevel.Information, "application_start", "success",
        "Stackverse backend is up and accepting requests",
        fields:
        [
            ("port", options.Port),
            ("db_host", options.DbHost),
            ("db_name", options.DbName),
            ("oidc_issuer_uri", options.OidcIssuerUri),
            ("oidc_jwks_uri", options.OidcJwksUri),
            ("seed_messages_dir", options.SeedMessagesDir),
            ("log_level", builder.Configuration["LOG_LEVEL"] ?? "info"),
            ("log_format", builder.Configuration["LOG_FORMAT"] ?? "json"),
            ("otel_sdk_disabled", !(otlpEndpointConfigured && otelExportEnabled)),
        ]));
app.Lifetime.ApplicationStopping.Register(() =>
    app.Logger.Event(LogLevel.Information, "application_stop", "success",
        "Stackverse backend shutting down"));

app.Run();

public partial class Program; // exposes the entry point to WebApplicationFactory
