namespace StackverseBackend.Bookmarks;

public sealed record BookmarkRequest(
    string? Url = null,
    string? Title = null,
    string? Notes = null,
    List<string>? Tags = null,
    Visibility? Visibility = null);

public sealed record BookmarkResponse(
    Guid Id,
    string Url,
    string Title,
    string? Notes,
    IReadOnlyList<string> Tags,
    Visibility Visibility,
    BookmarkStatus Status,
    string Owner,
    DateTime CreatedAt,
    DateTime UpdatedAt)
{
    public static BookmarkResponse Of(Bookmark bookmark) => new(
        bookmark.Id,
        bookmark.Url,
        bookmark.Title,
        bookmark.Notes,
        bookmark.Tags.Order(StringComparer.Ordinal).ToList(),
        bookmark.Visibility,
        bookmark.Status,
        bookmark.Owner,
        bookmark.CreatedAt,
        bookmark.UpdatedAt);
}

public sealed record BookmarkCursorPageResponse(IReadOnlyList<BookmarkResponse> Items, string? NextCursor);

public sealed record TagCountResponse(string Tag, long Count);

public sealed record TagListResponse(IReadOnlyList<TagCountResponse> Tags);
