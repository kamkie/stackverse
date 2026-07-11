namespace StackverseBackend.Common;

public sealed record PageResponse<T>(IReadOnlyList<T> Items, int Page, int Size, long TotalItems, int TotalPages);

public static class PageResponse
{
    public static PageResponse<T> Create<T>(IReadOnlyList<T> items, int page, int size, long totalItems) =>
        new(items, page, size, totalItems, (int)((totalItems + size - 1) / size));
}

public static class Paging
{
    /// <summary>Shared bounds for `page`/`size` query parameters (spec: size 1–100, default 20).</summary>
    public static void RequireValidPaging(int page, int size)
    {
        if (page < 0)
        {
            throw new BadRequestProblem("page must not be negative");
        }
        if (size is < 1 or > 100)
        {
            throw new BadRequestProblem("size must be between 1 and 100");
        }
    }

    /// <summary>
    /// Offset for a validated page/size pair. `page` alone fits an int, but
    /// `page * size` can overflow; past int.MaxValue any page is empty anyway,
    /// so clamping preserves the contract's empty-page answer for absurd offsets.
    /// </summary>
    public static int SkipOf(int page, int size) =>
        (int)Math.Min((long)page * size, int.MaxValue);

    public static void RequireMaxLength(string? value, int max, string name)
    {
        if (value is { } text && text.Length > max)
        {
            throw new BadRequestProblem($"{name} must be at most {max} characters");
        }
    }

    /// <summary>LIKE wildcards in user-supplied filters are literals, not patterns.</summary>
    public static string EscapeLike(string value) =>
        value.Replace(@"\", @"\\").Replace("%", @"\%").Replace("_", @"\_");
}
