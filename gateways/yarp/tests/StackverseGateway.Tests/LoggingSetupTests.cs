using Microsoft.AspNetCore.Builder;
using Microsoft.Extensions.DependencyInjection;
using Microsoft.Extensions.Logging;
using Microsoft.Extensions.Logging.Console;
using Microsoft.Extensions.Options;

namespace StackverseGateway.Tests;

public sealed class LoggingSetupTests
{
    [Fact]
    public async Task Configure_applies_debug_text_console_and_trace_scope_options()
    {
        var builder = WebApplication.CreateBuilder();
        builder.Configuration["LOG_LEVEL"] = "debug";
        builder.Configuration["LOG_FORMAT"] = "text";

        LoggingSetup.Configure(builder);

        Assert.Equal("Debug", builder.Configuration["Logging:LogLevel:Default"]);
        Assert.Equal("Debug", builder.Configuration["Logging:LogLevel:Microsoft.AspNetCore"]);
        await using var app = builder.Build();
        var console = app.Services.GetRequiredService<IOptionsMonitor<SimpleConsoleFormatterOptions>>()
            .CurrentValue;
        Assert.Equal("HH:mm:ss ", console.TimestampFormat);
        Assert.True(console.IncludeScopes);
        var factory = app.Services.GetRequiredService<IOptions<LoggerFactoryOptions>>().Value;
        Assert.Equal(
            ActivityTrackingOptions.TraceId | ActivityTrackingOptions.SpanId,
            factory.ActivityTrackingOptions);
    }

    [Fact]
    public async Task Configure_applies_error_json_console_with_rfc3339_utc_timestamps()
    {
        var builder = WebApplication.CreateBuilder();
        builder.Configuration["LOG_LEVEL"] = "error";
        builder.Configuration["LOG_FORMAT"] = "json";

        LoggingSetup.Configure(builder);

        Assert.Equal("Error", builder.Configuration["Logging:LogLevel:Default"]);
        Assert.Equal("Error", builder.Configuration["Logging:LogLevel:Microsoft.AspNetCore"]);
        await using var app = builder.Build();
        var console = app.Services.GetRequiredService<IOptionsMonitor<JsonConsoleFormatterOptions>>()
            .CurrentValue;
        Assert.True(console.UseUtcTimestamp);
        Assert.Equal("yyyy-MM-dd'T'HH:mm:ss.fff'Z'", console.TimestampFormat);
        Assert.True(console.IncludeScopes);
    }

    [Theory]
    [InlineData("error", LogLevel.Error)]
    [InlineData("warn", LogLevel.Warning)]
    [InlineData("info", LogLevel.Information)]
    [InlineData("debug", LogLevel.Debug)]
    [InlineData("DEBUG", LogLevel.Debug)]
    [InlineData(null, LogLevel.Information)] // the contract default
    [InlineData("verbose", LogLevel.Information)] // unknown values fall back to the default
    public void Log_level_maps_onto_the_dotnet_filter(string? value, LogLevel expected)
    {
        Assert.Equal(expected, LoggingSetup.MapLevel(value));
    }

    [Theory]
    [InlineData("text", true)]
    [InlineData("TEXT", true)]
    [InlineData("json", false)]
    [InlineData(null, false)] // JSON console is the default
    public void Log_format_defaults_to_json(string? value, bool expectText)
    {
        Assert.Equal(expectText, LoggingSetup.IsTextFormat(value));
    }

    [Theory]
    [InlineData(null, null)]
    [InlineData("/api/v1/bookmarks", "/api/v1/bookmarks")]
    [InlineData("line1\r\nline2", "line1\\nline2")] // newlines encoded, never raw (§6)
    [InlineData("a\0b\u001bc", "abc")] // other control characters stripped
    public void Client_controlled_log_fields_are_sanitized(string? value, string? expected)
    {
        Assert.Equal(expected, EventLog.Sanitize(value));
    }

    [Fact]
    public void Client_controlled_log_fields_are_length_capped()
    {
        var sanitized = EventLog.Sanitize(new string('a', 500));
        Assert.Equal(200 + 1, sanitized!.Length); // capped plus the ellipsis marker
        Assert.EndsWith("…", sanitized);
    }

    [Fact]
    public void Contract_event_keeps_message_and_structured_fields_separate_and_omits_nulls()
    {
        var logger = new RecordingLogger<LoggingSetupTests>();
        var exception = new InvalidOperationException("dependency failed");

        logger.Event(
            LogLevel.Warning,
            "dependency_call_failed",
            "timeout",
            "Redis did not answer",
            exception,
            ("dependency", "redis"),
            ("duration_ms", 125L),
            ("resource_id", null));

        var entry = Assert.Single(logger.Entries);
        Assert.Equal(LogLevel.Warning, entry.Level);
        Assert.Equal("Redis did not answer", entry.Message);
        Assert.Same(exception, entry.Exception);
        Assert.Equal("dependency_call_failed", entry.Fields["event"]);
        Assert.Equal("timeout", entry.Fields["outcome"]);
        Assert.Equal("redis", entry.Fields["dependency"]);
        Assert.Equal(125L, entry.Fields["duration_ms"]);
        Assert.False(entry.Fields.ContainsKey("resource_id"));
        Assert.DoesNotContain("dependency_call_failed", entry.Message, StringComparison.Ordinal);
    }
}
