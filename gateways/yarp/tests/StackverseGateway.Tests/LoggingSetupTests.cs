using Microsoft.Extensions.Logging;

namespace StackverseGateway.Tests;

public sealed class LoggingSetupTests
{
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
}
