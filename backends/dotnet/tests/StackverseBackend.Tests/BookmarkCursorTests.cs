using StackverseBackend.Bookmarks;
using StackverseBackend.Common;

namespace StackverseBackend.Tests;

public class BookmarkCursorTests
{
    [Fact]
    public void RoundTripsThroughTheOpaqueEncoding()
    {
        var cursor = new BookmarkCursor(
            new DateTime(2026, 7, 3, 12, 34, 56, 789, DateTimeKind.Utc).AddMicroseconds(123),
            Guid.NewGuid());

        var decoded = BookmarkCursor.Decode(cursor.Encode());

        Assert.Equal(cursor.CreatedAt, decoded.CreatedAt);
        Assert.Equal(cursor.Id, decoded.Id);
        Assert.Equal(DateTimeKind.Utc, decoded.CreatedAt.Kind);
    }

    [Theory]
    [InlineData("definitely-not-a-cursor")]
    [InlineData("!!!")]
    [InlineData("")]
    // base64url("no separator here")
    [InlineData("bm8gc2VwYXJhdG9yIGhlcmU")]
    // base64url("not-a-date|not-a-guid")
    [InlineData("bm90LWEtZGF0ZXxub3QtYS1ndWlk")]
    public void MalformedCursorsAreA400Problem(string cursor)
    {
        var problem = Assert.Throws<BadRequestProblem>(() => BookmarkCursor.Decode(cursor));
        Assert.Equal(StatusCodes.Status400BadRequest, problem.Status);
    }
}
