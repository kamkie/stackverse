using StackverseBackend.Accounts;
using StackverseBackend.Bookmarks;
using StackverseBackend.Common;
using StackverseBackend.Moderation;

namespace StackverseBackend.Tests;

public class WireTests
{
    [Fact]
    public void EnumsUseTheContractsKebabCaseWireValues()
    {
        Assert.Equal("private", Wire.Of(Visibility.Private));
        Assert.Equal("public", Wire.Of(Visibility.Public));
        Assert.Equal("active", Wire.Of(BookmarkStatus.Active));
        Assert.Equal("hidden", Wire.Of(BookmarkStatus.Hidden));
        Assert.Equal("broken-link", Wire.Of(ReportReason.BrokenLink));
        Assert.Equal("open", Wire.Of(ReportStatus.Open));
        Assert.Equal("blocked", Wire.Of(UserAccountStatus.Blocked));
    }

    [Fact]
    public void ParseIsTheExactInverse()
    {
        Assert.Equal(ReportReason.BrokenLink, Wire.Parse<ReportReason>("broken-link"));
        Assert.Null(Wire.Parse<ReportReason>("BROKEN-LINK"));
        Assert.Null(Wire.Parse<ReportReason>("dunno"));
        Assert.Null(Wire.Parse<ReportReason>(null));
    }

    [Fact]
    public void ParseQueryTurnsUnknownValuesIntoA400Problem()
    {
        Assert.Null(Wire.ParseQuery<Visibility>(null, "visibility"));
        Assert.Equal(Visibility.Public, Wire.ParseQuery<Visibility>("public", "visibility"));
        Assert.Throws<BadRequestProblem>(() => Wire.ParseQuery<Visibility>("everyone", "visibility"));
    }

    [Fact]
    public void ParseStoredNamesTheBadValueAndColumn()
    {
        var exception = Assert.Throws<InvalidOperationException>(
            () => Wire.ParseStored<ReportStatus>("pending", "reports.status"));

        Assert.Contains("ReportStatus", exception.Message);
        Assert.Contains("pending", exception.Message);
        Assert.Contains("reports.status", exception.Message);
    }
}
