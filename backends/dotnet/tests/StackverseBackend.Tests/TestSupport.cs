using Microsoft.EntityFrameworkCore;
using Microsoft.EntityFrameworkCore.Diagnostics;
using Npgsql;
using StackverseBackend.Accounts;
using StackverseBackend.Audit;
using StackverseBackend.Bookmarks;
using StackverseBackend.Data;
using StackverseBackend.Messages;
using StackverseBackend.Moderation;

namespace StackverseBackend.Tests;

internal static class TestData
{
    public static DateTime At(int minutes = 0) =>
        new DateTime(2026, 7, 5, 12, 0, 0, DateTimeKind.Utc) + TimeSpan.FromMinutes(minutes);

    public static Bookmark Bookmark(
        Guid? id = null,
        string owner = "owner",
        Visibility visibility = Visibility.Private,
        BookmarkStatus status = BookmarkStatus.Active,
        DateTime? createdAt = null,
        IReadOnlyList<string>? tags = null) => new()
        {
            Id = id ?? Guid.NewGuid(),
            Owner = owner,
            Url = $"https://example.com/{id ?? Guid.NewGuid()}",
            Title = $"Bookmark {owner}",
            Tags = tags?.ToList() ?? [],
            Visibility = visibility,
            Status = status,
            CreatedAt = createdAt ?? At(),
            UpdatedAt = createdAt ?? At(),
        };

    public static Report Report(
        Guid bookmarkId,
        Guid? id = null,
        string reporter = "reporter",
        ReportReason reason = ReportReason.Spam,
        ReportStatus status = ReportStatus.Open,
        DateTime? createdAt = null) => new()
        {
            Id = id ?? Guid.NewGuid(),
            BookmarkId = bookmarkId,
            Reporter = reporter,
            Reason = reason,
            Status = status,
            CreatedAt = createdAt ?? At(),
        };

    public static Message Message(
        string key,
        string language,
        string text,
        Guid? id = null,
        string? description = null) => new()
        {
            Id = id ?? Guid.NewGuid(),
            Key = key,
            Language = language,
            Text = text,
            Description = description,
            CreatedAt = At(),
            UpdatedAt = At(),
        };

    public static UserAccount User(
        string username,
        UserAccountStatus status = UserAccountStatus.Active,
        DateTime? firstSeen = null,
        DateTime? lastSeen = null,
        string? blockedReason = null) => new()
        {
            Username = username,
            FirstSeen = firstSeen ?? At(-10),
            LastSeen = lastSeen ?? At(-5),
            Status = status,
            BlockedReason = blockedReason,
        };

    public static AuditEntry Audit(
        string actor,
        string action,
        string targetType,
        string targetId,
        DateTime createdAt,
        string? detail = null) => new()
        {
            Actor = actor,
            Action = action,
            TargetType = targetType,
            TargetId = targetId,
            Detail = detail,
            CreatedAt = createdAt,
        };
}

internal sealed record RecordedLog(
    LogLevel Level,
    EventId EventId,
    string Message,
    Exception? Exception,
    IReadOnlyDictionary<string, object?> Fields);

internal sealed class RecordingLogger<T> : ILogger<T>
{
    public List<RecordedLog> Entries { get; } = [];

    public IDisposable? BeginScope<TState>(TState state) where TState : notnull => NullScope.Instance;

    public bool IsEnabled(LogLevel logLevel) => true;

    public void Log<TState>(
        LogLevel logLevel,
        EventId eventId,
        TState state,
        Exception? exception,
        Func<TState, Exception?, string> formatter)
    {
        var fields = new Dictionary<string, object?>(StringComparer.Ordinal);
        if (state is IEnumerable<KeyValuePair<string, object?>> pairs)
        {
            foreach (var pair in pairs)
            {
                fields[pair.Key] = pair.Value;
            }
        }
        Entries.Add(new RecordedLog(logLevel, eventId, formatter(state, exception), exception, fields));
    }

    private sealed class NullScope : IDisposable
    {
        public static NullScope Instance { get; } = new();

        public void Dispose()
        {
        }
    }
}

internal sealed class FailingSaveAppDbContext(DbContextOptions<AppDbContext> options) : AppDbContext(options)
{
    private Exception? _nextSaveFailure;

    public static FailingSaveAppDbContext Create() => new(
        new DbContextOptionsBuilder<AppDbContext>()
            .UseInMemoryDatabase(Guid.NewGuid().ToString())
            .ConfigureWarnings(warnings => warnings.Ignore(InMemoryEventId.TransactionIgnoredWarning))
            .Options);

    public void FailNextSave(Exception exception) => _nextSaveFailure = exception;

    public override Task<int> SaveChangesAsync(CancellationToken cancellationToken = default)
    {
        if (_nextSaveFailure is not { } exception)
        {
            return base.SaveChangesAsync(cancellationToken);
        }
        _nextSaveFailure = null;
        return Task.FromException<int>(exception);
    }
}

internal static class TestDatabaseFailure
{
    public static DbUpdateException UniqueViolation(string constraintName) =>
        UpdateException(PostgresErrorCodes.UniqueViolation, constraintName);

    public static DbUpdateException ForeignKeyViolation() =>
        UpdateException(PostgresErrorCodes.ForeignKeyViolation, "fk_reports_bookmarks");

    private static DbUpdateException UpdateException(string sqlState, string constraintName) => new(
        "Simulated persistence race",
        new PostgresException(
            "simulated database failure",
            "ERROR",
            "ERROR",
            sqlState,
            detail: "",
            hint: "",
            position: 0,
            internalPosition: 0,
            internalQuery: "",
            where: "",
            schemaName: "public",
            tableName: "",
            columnName: "",
            dataTypeName: "",
            constraintName,
            file: "",
            line: "",
            routine: ""));
}
