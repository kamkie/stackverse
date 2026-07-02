using Microsoft.Extensions.Logging.Console;

namespace StackverseGateway;

/// <summary>
/// Maps the repo-wide logging env vars (docs/LOGGING.md §8) onto the .NET logging
/// stack: <c>LOG_LEVEL</c> becomes the default category filter and <c>LOG_FORMAT</c>
/// picks the console formatter — structured JSON by default, human-readable text
/// for local dev. Activity tracking puts the current trace/span ids into logging
/// scopes, so console lines link to their trace whenever a request activity exists.
/// </summary>
public static class LoggingSetup
{
    public static void Configure(WebApplicationBuilder builder)
    {
        var level = MapLevel(builder.Configuration["LOG_LEVEL"]);
        builder.Configuration["Logging:LogLevel:Default"] = level.ToString();
        // Framework categories stay capped at Warning (appsettings.json) so hosting
        // noise — health-probe request lines included — stays out of INFO logs;
        // `debug` lifts the cap for auth/proxy diagnosis, `error` tightens it further.
        if (level is LogLevel.Debug or LogLevel.Error)
        {
            builder.Configuration["Logging:LogLevel:Microsoft.AspNetCore"] = level.ToString();
        }

        builder.Logging.ClearProviders();
        if (IsTextFormat(builder.Configuration["LOG_FORMAT"]))
        {
            builder.Logging.AddSimpleConsole(console =>
            {
                console.TimestampFormat = "HH:mm:ss ";
                console.IncludeScopes = true;
            });
        }
        else
        {
            builder.Logging.AddJsonConsole(console =>
            {
                // RFC 3339 UTC with millisecond precision (docs/LOGGING.md §2)
                console.UseUtcTimestamp = true;
                console.TimestampFormat = "yyyy-MM-dd'T'HH:mm:ss.fff'Z'";
                console.IncludeScopes = true;
            });
        }
        builder.Logging.Configure(options =>
            options.ActivityTrackingOptions = ActivityTrackingOptions.TraceId | ActivityTrackingOptions.SpanId);
    }

    internal static LogLevel MapLevel(string? value) => value?.ToLowerInvariant() switch
    {
        "error" => LogLevel.Error,
        "warn" => LogLevel.Warning,
        "debug" => LogLevel.Debug,
        _ => LogLevel.Information, // the contract default (`info`)
    };

    internal static bool IsTextFormat(string? value) => string.Equals(value, "text", StringComparison.OrdinalIgnoreCase);
}
