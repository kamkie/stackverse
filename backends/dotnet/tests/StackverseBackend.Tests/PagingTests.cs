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
}
