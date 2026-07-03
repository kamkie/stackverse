namespace StackverseBackend.Audit;

/// <summary>Append-only (SPEC rule 18) — rows are inserted and read, never updated or deleted.</summary>
public class AuditEntry
{
    public Guid Id { get; init; } = Guid.NewGuid();
    public required string Actor { get; init; }
    public required string Action { get; init; }
    public required string TargetType { get; init; }
    public required string TargetId { get; init; }

    /// <summary>JSON snapshot of the change, stored as jsonb.</summary>
    public string? Detail { get; init; }

    public DateTime CreatedAt { get; init; }
}
