using StackverseBackend.Common;

namespace StackverseBackend.Tests;

public class PagingTests
{
    [Fact]
    public void SkipIsPageTimesSize() =>
        Assert.Equal(40, Paging.SkipOf(2, 20));

    [Fact]
    public void AbsurdOffsetsClampInsteadOfOverflowing() =>
        // int.MaxValue * 100 would wrap negative and turn a valid (if silly)
        // request into a 500; past int.MaxValue every page is empty anyway
        Assert.Equal(int.MaxValue, Paging.SkipOf(int.MaxValue, 100));

    [Theory]
    [InlineData(-1, 20)]
    [InlineData(0, 0)]
    [InlineData(0, 101)]
    public void OutOfContractPagingIsA400Problem(int page, int size) =>
        Assert.Throws<BadRequestProblem>(() => Paging.RequireValidPaging(page, size));

    [Fact]
    public void Like_filter_characters_are_escaped_as_literals() =>
        Assert.Equal(@"path\\\%\_value", Paging.EscapeLike(@"path\%_value"));

    [Fact]
    public void Overlong_query_is_rejected_but_absent_and_bounded_values_are_allowed()
    {
        Paging.RequireMaxLength(null, 3, "q");
        Paging.RequireMaxLength("abc", 3, "q");

        var problem = Assert.Throws<BadRequestProblem>(() => Paging.RequireMaxLength("abcd", 3, "q"));
        Assert.Equal("q must be at most 3 characters", problem.Detail);
    }

    [Fact]
    public void Page_response_zero_fills_total_pages_for_empty_results()
    {
        var empty = PageResponse.Create(Array.Empty<string>(), page: 2, size: 20, totalItems: 0);
        var partial = PageResponse.Create(["one"], page: 1, size: 20, totalItems: 21);

        Assert.Equal(0, empty.TotalPages);
        Assert.Equal(2, partial.TotalPages);
    }
}
