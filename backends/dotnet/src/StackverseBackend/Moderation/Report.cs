namespace StackverseBackend.Moderation;

public enum ReportReason
{
    Spam,
    Offensive,
    BrokenLink, // serialized/stored as "broken-link" (kebab-case policy)
    Other,
}

public enum ReportStatus
{
    Open,
    Dismissed,
    Actioned,
}

public class Report
{
    public Guid Id { get; init; } = Guid.NewGuid();
    public required Guid BookmarkId { get; init; }
    public required string Reporter { get; init; }
    public ReportReason Reason { get; set; }
    public string? Comment { get; set; }
    public ReportStatus Status { get; set; }
    public string? ResolvedBy { get; set; }
    public DateTime? ResolvedAt { get; set; }
    public string? ResolutionNote { get; set; }
    public DateTime CreatedAt { get; init; }
}
