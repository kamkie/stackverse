using StackverseBackend.Audit;
using StackverseBackend.Bookmarks;
using StackverseBackend.Common;
using StackverseBackend.Messages;
using StackverseBackend.Moderation;

namespace StackverseBackend.Tests;

public class PersistenceErrorMappingTests
{
    [Fact]
    public async Task Message_create_maps_unique_constraint_race_to_409()
    {
        await using var db = FailingSaveAppDbContext.Create();
        var service = new MessageService(db, new AuditService(db), new RecordingLogger<MessageService>());
        db.FailNextSave(TestDatabaseFailure.UniqueViolation("uq_messages_key_language"));

        var problem = await Assert.ThrowsAsync<ConflictProblem>(() => service.CreateAsync(
            "admin",
            new MessageRequest("ui.race", "en", "Race")));

        Assert.Equal(StatusCodes.Status409Conflict, problem.Status);
        Assert.Contains("already exists", problem.Detail);
    }

    [Fact]
    public async Task Message_update_maps_unique_constraint_race_to_409()
    {
        await using var db = FailingSaveAppDbContext.Create();
        var message = TestData.Message("ui.race", "en", "Before");
        db.Messages.Add(message);
        await db.SaveChangesAsync();
        var service = new MessageService(db, new AuditService(db), new RecordingLogger<MessageService>());
        db.FailNextSave(TestDatabaseFailure.UniqueViolation("uq_messages_key_language"));

        var problem = await Assert.ThrowsAsync<ConflictProblem>(() => service.UpdateAsync(
            "admin",
            message.Id,
            new MessageRequest("ui.race", "en", "After")));

        Assert.Equal(StatusCodes.Status409Conflict, problem.Status);
    }

    [Fact]
    public async Task Report_create_maps_unique_constraint_race_to_409()
    {
        await using var db = FailingSaveAppDbContext.Create();
        var bookmark = TestData.Bookmark(owner: "owner", visibility: Visibility.Public);
        db.Bookmarks.Add(bookmark);
        await db.SaveChangesAsync();
        var service = new ModerationService(db, new AuditService(db), new RecordingLogger<ModerationService>());
        db.FailNextSave(TestDatabaseFailure.UniqueViolation("uq_reports_one_open_per_reporter"));

        var problem = await Assert.ThrowsAsync<ConflictProblem>(() => service.ReportAsync(
            "reporter",
            bookmark.Id,
            new ReportRequest("spam")));

        Assert.Equal(StatusCodes.Status409Conflict, problem.Status);
    }

    [Fact]
    public async Task Report_create_maps_bookmark_delete_race_to_404()
    {
        await using var db = FailingSaveAppDbContext.Create();
        var bookmark = TestData.Bookmark(owner: "owner", visibility: Visibility.Public);
        db.Bookmarks.Add(bookmark);
        await db.SaveChangesAsync();
        var service = new ModerationService(db, new AuditService(db), new RecordingLogger<ModerationService>());
        db.FailNextSave(TestDatabaseFailure.ForeignKeyViolation());

        var problem = await Assert.ThrowsAsync<NotFoundProblem>(() => service.ReportAsync(
            "reporter",
            bookmark.Id,
            new ReportRequest("spam")));

        Assert.Equal(StatusCodes.Status404NotFound, problem.Status);
    }

    [Fact]
    public async Task Report_reopen_maps_partial_unique_constraint_race_to_409()
    {
        await using var db = FailingSaveAppDbContext.Create();
        var bookmark = TestData.Bookmark(owner: "owner", visibility: Visibility.Public);
        var report = TestData.Report(bookmark.Id, reporter: "reporter", status: ReportStatus.Dismissed);
        db.Bookmarks.Add(bookmark);
        db.Reports.Add(report);
        await db.SaveChangesAsync();
        var service = new ModerationService(db, new AuditService(db), new RecordingLogger<ModerationService>());
        db.FailNextSave(TestDatabaseFailure.UniqueViolation("uq_reports_one_open_per_reporter"));

        var problem = await Assert.ThrowsAsync<ConflictProblem>(() => service.ResolveAsync(
            "moderator",
            report.Id,
            new ReportResolutionRequest("open")));

        Assert.Equal(StatusCodes.Status409Conflict, problem.Status);
    }
}
