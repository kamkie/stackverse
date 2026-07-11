using StackverseBackend.Data;

namespace StackverseBackend.Tests;

public class EventAndDependencyLoggingTests
{
    [Fact]
    public void Contract_event_keeps_prose_separate_and_drops_null_fields()
    {
        var logger = new RecordingLogger<EventAndDependencyLoggingTests>();

        logger.Event(
            LogLevel.Information,
            "report_updated",
            "success",
            "Report updated by its reporter",
            fields:
            [
                ("actor", "sensitive-user-123"),
                ("resource_id", "report-1"),
                ("comment", null),
            ]);

        var entry = Assert.Single(logger.Entries);
        Assert.Equal(LogLevel.Information, entry.Level);
        Assert.Equal("Report updated by its reporter", entry.Message);
        Assert.Equal("report_updated", entry.Fields["event"]);
        Assert.Equal("success", entry.Fields["outcome"]);
        Assert.Equal("sensitive-user-123", entry.Fields["actor"]);
        Assert.False(entry.Fields.ContainsKey("comment"));
        Assert.DoesNotContain("sensitive-user-123", entry.Message);
    }

    [Fact]
    public void Database_failure_log_has_stable_dependency_duration_and_error_code_fields()
    {
        var logger = new RecordingLogger<EventAndDependencyLoggingTests>();
        var exception = new InvalidOperationException("connection unavailable");

        logger.LogDbFailure(exception, TimeSpan.FromMilliseconds(42.9), "connection");

        var entry = Assert.Single(logger.Entries);
        Assert.Equal(LogLevel.Error, entry.Level);
        Assert.Same(exception, entry.Exception);
        Assert.Equal("dependency_call_failed", entry.Fields["event"]);
        Assert.Equal("failure", entry.Fields["outcome"]);
        Assert.Equal("postgresql", entry.Fields["dependency"]);
        Assert.Equal(42L, entry.Fields["duration_ms"]);
        Assert.Equal("InvalidOperationException", entry.Fields["error_code"]);
        Assert.False(entry.Fields.ContainsKey("query"));
    }
}
