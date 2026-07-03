using StackverseBackend.Messages;

namespace StackverseBackend.Tests;

public class LanguageResolverTests
{
    private static readonly string[] Supported = ["en", "pl"];

    [Fact]
    public void ExplicitLangParameterWins() =>
        Assert.Equal("pl", LanguageResolver.Resolve("pl", "en", Supported));

    [Fact]
    public void UnsupportedLangFallsToAcceptLanguage() =>
        Assert.Equal("pl", LanguageResolver.Resolve("zz", "pl", Supported));

    [Fact]
    public void UnsupportedEverythingFallsToEnglish() =>
        Assert.Equal("en", LanguageResolver.Resolve("zz", "xx, yy;q=0.5", Supported));

    [Fact]
    public void MissingInputsFallToEnglish() =>
        Assert.Equal("en", LanguageResolver.Resolve(null, null, Supported));

    [Fact]
    public void AcceptLanguageIsQualityOrdered() =>
        // pl;q=0.8 beats en;q=0.5 regardless of listing order; unsupported zz is skipped
        Assert.Equal("pl", LanguageResolver.Resolve(null, "en;q=0.5, zz, pl;q=0.8", Supported));

    [Fact]
    public void RegionSubtagsResolveToThePrimaryLanguage() =>
        Assert.Equal("pl", LanguageResolver.Resolve(null, "pl-PL", Supported));

    [Fact]
    public void MalformedAcceptLanguageFallsToEnglish() =>
        Assert.Equal("en", LanguageResolver.Resolve(null, ";;;=,q", Supported));

    [Fact]
    public void ZeroQualityEntriesAreIgnored() =>
        Assert.Equal("en", LanguageResolver.Resolve(null, "pl;q=0", Supported));
}
