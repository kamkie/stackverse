using StackverseBackend.Bookmarks;

namespace StackverseBackend.Moderation;

// `reason` and `resolution` stay strings so an unknown value becomes a field-level
// validation error with its `validation.*` key, not a JSON parse failure.
public sealed record ReportRequest(string? Reason = null, string? Comment = null);

public sealed record ReportResolutionRequest(string? Resolution = null, string? Note = null);

public sealed record BookmarkStatusRequest(BookmarkStatus? Status = null, string? Note = null);

public sealed record ReportResponse(
    Guid Id,
    Guid BookmarkId,
    string Reporter,
    ReportReason Reason,
    string? Comment,
    ReportStatus Status,
    string? ResolvedBy,
    DateTime? ResolvedAt,
    string? ResolutionNote,
    DateTime CreatedAt)
{
    public static ReportResponse Of(Report report) => new(
        report.Id,
        report.BookmarkId,
        report.Reporter,
        report.Reason,
        report.Comment,
        report.Status,
        report.ResolvedBy,
        report.ResolvedAt,
        report.ResolutionNote,
        report.CreatedAt);
}
