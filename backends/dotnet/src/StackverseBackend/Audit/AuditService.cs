using System.Text.Json;
using StackverseBackend.Common;
using StackverseBackend.Data;

namespace StackverseBackend.Audit;

public sealed class AuditService(AppDbContext db)
{
    /// <summary>
    /// Stages an audit entry in the current unit of work; the surrounding service's
    /// SaveChanges persists it atomically with the mutation it records.
    /// </summary>
    public void Record(string actor, string action, string targetType, string targetId, Dictionary<string, object?>? detail = null)
    {
        db.AuditEntries.Add(new AuditEntry
        {
            Actor = actor,
            Action = action,
            TargetType = targetType,
            TargetId = targetId,
            Detail = detail is null ? null : JsonSerializer.Serialize(detail),
            CreatedAt = Clock.UtcNow(),
        });
    }
}
