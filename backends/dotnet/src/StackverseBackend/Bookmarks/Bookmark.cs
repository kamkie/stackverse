namespace StackverseBackend.Bookmarks;

// Wire values come from the kebab-case JSON enum policy in Program.cs; the same
// lowercase strings are stored in PostgreSQL (see AppDbContext).

public enum Visibility
{
    Private,
    Public,
}

public enum BookmarkStatus
{
    Active,
    Hidden,
}

public class Bookmark
{
    public Guid Id { get; init; } = Guid.NewGuid();
    public required string Owner { get; init; }
    public required string Url { get; set; }
    public required string Title { get; set; }
    public string? Notes { get; set; }

    /// <summary>Normalized (trimmed, lowercased, deduplicated); a PostgreSQL text[] column.</summary>
    public List<string> Tags { get; set; } = [];

    public Visibility Visibility { get; set; }
    public BookmarkStatus Status { get; set; }
    public DateTime CreatedAt { get; init; }
    public DateTime UpdatedAt { get; set; }
}
