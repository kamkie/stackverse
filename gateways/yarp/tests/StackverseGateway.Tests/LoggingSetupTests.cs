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
}
