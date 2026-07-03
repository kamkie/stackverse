using System.Text.RegularExpressions;
using Microsoft.EntityFrameworkCore;
using StackverseBackend.Common;
using StackverseBackend.Data;

namespace StackverseBackend.Bookmarks;

public sealed record BookmarkListQuery(IReadOnlyList<string> Tags, string? Q, Visibility? Visibility);

public sealed record BookmarkSlice(IReadOnlyList<Bookmark> Items, BookmarkCursor? NextCursor);

public sealed partial class BookmarkService(AppDbContext db)
{
    [GeneratedRegex("^[a-z0-9-]{1,30}$")]
    private static partial Regex TagPattern();

    public async Task<Bookmark> CreateAsync(string owner, BookmarkRequest request)
    {
        var input = Validate(request);
        var now = Clock.UtcNow();
        var bookmark = new Bookmark
        {
            Owner = owner,
            Url = input.Url,
            Title = input.Title,
            Notes = input.Notes,
            Tags = input.Tags,
            Visibility = input.Visibility,
            Status = BookmarkStatus.Active,
            CreatedAt = now,
            UpdatedAt = now,
        };
        db.Bookmarks.Add(bookmark);
        await db.SaveChangesAsync();
        return bookmark;
    }

    public async Task<Bookmark> GetAsync(string? caller, Guid id)
    {
        var bookmark = await db.Bookmarks.AsNoTracking().SingleOrDefaultAsync(b => b.Id == id)
            ?? throw new NotFoundProblem();
        if (!IsVisibleTo(bookmark, caller))
        {
            throw new NotFoundProblem();
        }
        return bookmark;
    }

    public async Task<Bookmark> UpdateAsync(string caller, Guid id, BookmarkRequest request)
    {
        // Lock the row and do the read → hidden-publish check → write in one
        // transaction: the moderator status endpoint takes the same FOR UPDATE
        // lock, so a concurrent hide cannot slip between the check and the write
        // (SPEC rule 15).
        await using var transaction = await db.Database.BeginTransactionAsync();
        var bookmark = await ForUpdateAsync(id) ?? throw new NotFoundProblem();
        // rule 1: a non-owner never learns the bookmark exists — 404, not 403
        if (bookmark.Owner != caller)
        {
            throw new NotFoundProblem();
        }
        var input = Validate(request);
        // SPEC rule 15: a moderation-hidden bookmark cannot be (re)published by its owner
        if (bookmark.Status == BookmarkStatus.Hidden && input.Visibility == Visibility.Public)
        {
            throw new ConflictProblem(
                "This bookmark was hidden by moderation and cannot be made public.",
                detailKey: "error.bookmark.hidden-publish");
        }
        bookmark.Url = input.Url;
        bookmark.Title = input.Title;
        bookmark.Notes = input.Notes;
        bookmark.Tags = input.Tags;
        bookmark.Visibility = input.Visibility;
        bookmark.UpdatedAt = Clock.UtcNow();
        await db.SaveChangesAsync();
        await transaction.CommitAsync();
        return bookmark;
    }

    public async Task DeleteAsync(string caller, Guid id)
    {
        db.Bookmarks.Remove(await OwnedByCallerAsync(caller, id));
        await db.SaveChangesAsync();
    }

    public async Task<(IReadOnlyList<Bookmark> Items, long Total)> ListOffsetAsync(
        string? caller, BookmarkListQuery query, int page, int size)
    {
        var filtered = QueryFor(caller, query);
        var total = await filtered.LongCountAsync();
        var items = await NewestFirst(filtered).Skip(Paging.SkipOf(page, size)).Take(size).AsNoTracking().ToListAsync();
        return (items, total);
    }

    public async Task<BookmarkSlice> ListKeysetAsync(
        string? caller, BookmarkListQuery query, BookmarkCursor? cursor, int size)
    {
        var filtered = QueryFor(caller, query);
        if (cursor is not null)
        {
            // strictly before the cursor position in (createdAt, id) descending order
            filtered = filtered.Where(b =>
                b.CreatedAt < cursor.CreatedAt
                || (b.CreatedAt == cursor.CreatedAt && b.Id.CompareTo(cursor.Id) < 0));
        }
        var fetched = await NewestFirst(filtered).Take(size + 1).AsNoTracking().ToListAsync();
        var items = fetched.Take(size).ToList();
        var nextCursor = fetched.Count > size ? BookmarkCursor.Of(items[^1]) : null;
        return new BookmarkSlice(items, nextCursor);
    }

    // the quoted aliases match TagCountResponse's property names, which the outer
    // SELECT that EF composes around raw SQL references case-sensitively
    public async Task<IReadOnlyList<TagCountResponse>> CountTagsByOwnerAsync(string owner) =>
        await db.Database.SqlQuery<TagCountResponse>($"""
            select t.tag as "Tag", count(*) as "Count"
            from bookmarks b cross join lateral unnest(b.tags) as t(tag)
            where b.owner = {owner}
            group by t.tag
            order by count(*) desc, t.tag
            """).ToListAsync();

    public async Task<IReadOnlyList<TagCountResponse>> TopTagsAsync(int limit) =>
        await db.Database.SqlQuery<TagCountResponse>($"""
            select t.tag as "Tag", count(*) as "Count"
            from bookmarks b cross join lateral unnest(b.tags) as t(tag)
            group by t.tag
            order by count(*) desc, t.tag
            limit {limit}
            """).ToListAsync();

    /// <summary>Newest first; `id` breaks `createdAt` ties so the order is total — a keyset requirement.</summary>
    private static IOrderedQueryable<Bookmark> NewestFirst(IQueryable<Bookmark> query) =>
        query.OrderByDescending(b => b.CreatedAt).ThenByDescending(b => b.Id);

    private static bool IsVisibleTo(Bookmark bookmark, string? caller) =>
        bookmark.Owner == caller || (bookmark.Visibility == Visibility.Public && bookmark.Status == BookmarkStatus.Active);

    /// <summary>
    /// Reads a bookmark row under FOR UPDATE for the owner update; the moderator
    /// status endpoint takes the same lock, so the hidden-publish check (rule 15)
    /// and a concurrent hide serialize on the row instead of racing.
    /// </summary>
    private Task<Bookmark?> ForUpdateAsync(Guid id) =>
        db.Bookmarks.FromSql($"select * from bookmarks where id = {id} for update").SingleOrDefaultAsync();

    /// <summary>Rule 1: a non-owner never learns the bookmark exists — 404, not 403.</summary>
    private async Task<Bookmark> OwnedByCallerAsync(string caller, Guid id)
    {
        var bookmark = await db.Bookmarks.SingleOrDefaultAsync(b => b.Id == id) ?? throw new NotFoundProblem();
        if (bookmark.Owner != caller)
        {
            throw new NotFoundProblem();
        }
        return bookmark;
    }

    /// <summary>
    /// Rule 2 + 3: `visibility=public` is the anonymous-capable public feed across all
    /// owners (hidden excluded); every other listing is the caller's own bookmarks.
    /// </summary>
    private IQueryable<Bookmark> QueryFor(string? caller, BookmarkListQuery query)
    {
        IQueryable<Bookmark> filtered;
        if (query.Visibility == Visibility.Public)
        {
            filtered = db.Bookmarks.Where(b => b.Visibility == Visibility.Public && b.Status == BookmarkStatus.Active);
        }
        else
        {
            var owner = caller ?? throw new UnauthorizedProblem();
            filtered = db.Bookmarks.Where(b => b.Owner == owner);
            if (query.Visibility is { } visibility)
            {
                filtered = filtered.Where(b => b.Visibility == visibility);
            }
        }
        foreach (var tag in query.Tags)
        {
            filtered = filtered.Where(b => b.Tags.Contains(tag));
        }
        if (query.Q is { } q && !string.IsNullOrWhiteSpace(q))
        {
            var pattern = $"%{Paging.EscapeLike(q)}%";
            filtered = filtered.Where(b =>
                EF.Functions.ILike(b.Title, pattern, @"\")
                || (b.Notes != null && EF.Functions.ILike(b.Notes, pattern, @"\")));
        }
        return filtered;
    }

    internal sealed record ValidatedBookmark(string Url, string Title, string? Notes, List<string> Tags, Visibility Visibility);

    internal static List<string> ValidateQueryTags(IEnumerable<string>? tags)
    {
        var normalized = (tags ?? Enumerable.Empty<string>())
            .Select(tag => tag.Trim().ToLowerInvariant())
            .ToList();

        var validator = new Validator();
        validator.Check(normalized.All(tag => TagPattern().IsMatch(tag)), "tag", "validation.tag.invalid");
        validator.ThrowIfInvalid();
        return normalized;
    }

    internal static ValidatedBookmark Validate(BookmarkRequest request)
    {
        var validator = new Validator();

        var url = request.Url?.Trim() ?? "";
        if (url.Length == 0)
        {
            validator.Reject("url", "validation.url.required");
        }
        else
        {
            validator.Check(url.Length <= 2000 && IsHttpUrl(url), "url", "validation.url.invalid");
        }

        var title = request.Title?.Trim() ?? "";
        validator.Check(title.Length > 0, "title", "validation.title.required");
        validator.Check(title.Length <= 200, "title", "validation.title.too-long");

        validator.Check((request.Notes?.Length ?? 0) <= 4000, "notes", "validation.notes.too-long");

        // normalized before validation: " Kotlin " and "kotlin" are the same tag
        var tags = request.Tags ?? [];
        var normalized = tags.Select(tag => tag.Trim().ToLowerInvariant()).Distinct().ToList();
        validator.Check(normalized.Count <= 10, "tags", "validation.tags.too-many");
        validator.Check(normalized.All(tag => TagPattern().IsMatch(tag)), "tags", "validation.tag.invalid");

        validator.ThrowIfInvalid();
        return new ValidatedBookmark(url, title, request.Notes, normalized, request.Visibility ?? Visibility.Private);
    }

    internal static bool IsHttpUrl(string url) =>
        Uri.TryCreate(url, UriKind.Absolute, out var uri)
        && (uri.Scheme == Uri.UriSchemeHttp || uri.Scheme == Uri.UriSchemeHttps)
        && uri.Host.Length > 0;
}
