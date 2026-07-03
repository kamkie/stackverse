using StackverseBackend.Bookmarks;
using StackverseBackend.Common;

namespace StackverseBackend.Tests;

public class BookmarkValidationTests
{
    [Fact]
    public void AppliesDefaultsAndNormalizesTags()
    {
        var validated = BookmarkService.Validate(new BookmarkRequest(
            Url: " https://example.com ",
            Title: "  a title  ",
            Tags: [" Kotlin ", "kotlin", "dotnet"]));

        Assert.Equal("https://example.com", validated.Url);
        Assert.Equal("a title", validated.Title);
        Assert.Equal(["kotlin", "dotnet"], validated.Tags);
        Assert.Equal(Visibility.Private, validated.Visibility);
    }

    [Fact]
    public void MissingUrlAndTitleAreReportedTogether()
    {
        var problem = Assert.Throws<ValidationProblem>(() => BookmarkService.Validate(new BookmarkRequest()));
        Assert.Equal(
            ["validation.url.required", "validation.title.required"],
            problem.Violations.Select(v => v.MessageKey));
    }

    [Theory]
    [InlineData("/not/absolute")]
    [InlineData("ftp://example.com")]
    [InlineData("https://")]
    public void RejectsNonHttpUrls(string url)
    {
        var problem = Assert.Throws<ValidationProblem>(
            () => BookmarkService.Validate(new BookmarkRequest(Url: url, Title: "t")));
        Assert.Contains(problem.Violations, v => v.MessageKey == "validation.url.invalid");
    }

    [Fact]
    public void RejectsOverlongFieldsAndBadTags()
    {
        var problem = Assert.Throws<ValidationProblem>(() => BookmarkService.Validate(new BookmarkRequest(
            Url: "https://example.com",
            Title: new string('x', 201),
            Notes: new string('x', 4001),
            Tags: ["no spaces!"])));
        Assert.Equal(
            ["validation.title.too-long", "validation.notes.too-long", "validation.tag.invalid"],
            problem.Violations.Select(v => v.MessageKey));
    }

    [Fact]
    public void RejectsMoreThanTenTags()
    {
        var problem = Assert.Throws<ValidationProblem>(() => BookmarkService.Validate(new BookmarkRequest(
            Url: "https://example.com",
            Title: "t",
            Tags: Enumerable.Range(0, 11).Select(i => $"tag-{i}").ToList())));
        Assert.Contains(problem.Violations, v => v.MessageKey == "validation.tags.too-many");
    }

    [Fact]
    public void DuplicateTagsCountOnceAgainstTheLimit()
    {
        var validated = BookmarkService.Validate(new BookmarkRequest(
            Url: "https://example.com",
            Title: "t",
            Tags: Enumerable.Repeat("same", 11).ToList()));
        Assert.Equal(["same"], validated.Tags);
    }

    [Fact]
    public void QueryTagsAreNormalizedAndValidated()
    {
        Assert.Equal(["kotlin", "web"], BookmarkService.ValidateQueryTags([" Kotlin ", "web"]));

        var problem = Assert.Throws<ValidationProblem>(() => BookmarkService.ValidateQueryTags(["valid", "no spaces!"]));
        var violation = Assert.Single(problem.Violations);
        Assert.Equal("tag", violation.Field);
        Assert.Equal("validation.tag.invalid", violation.MessageKey);
    }
}
