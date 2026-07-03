namespace StackverseBackend.Tests;

public class LoggingSetupTests
{
    [Theory]
    [InlineData("error", LogLevel.Error)]
    [InlineData("warn", LogLevel.Warning)]
    [InlineData("info", LogLevel.Information)]
    [InlineData("debug", LogLevel.Debug)]
    [InlineData("DEBUG", LogLevel.Debug)]
    [InlineData(null, LogLevel.Information)]
    [InlineData("verbose", LogLevel.Information)]
    public void MapsTheContractLevelsOntoDotnetLevels(string? value, LogLevel expected) =>
        Assert.Equal(expected, LoggingSetup.MapLevel(value));

    [Theory]
    [InlineData("text", true)]
    [InlineData("TEXT", true)]
    [InlineData("json", false)]
    [InlineData(null, false)]
    public void TextFormatIsAnExplicitOptIn(string? value, bool expected) =>
        Assert.Equal(expected, LoggingSetup.IsTextFormat(value));
}
