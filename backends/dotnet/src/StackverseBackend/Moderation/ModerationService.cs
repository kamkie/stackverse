using Microsoft.EntityFrameworkCore;
using Npgsql;
using StackverseBackend.Audit;
using StackverseBackend.Bookmarks;
using StackverseBackend.Common;
using StackverseBackend.Data;

namespace StackverseBackend.Moderation;

public sealed class ModerationService(AppDbContext db, AuditService auditService, ILogger<ModerationService> logger)
{
    // moderation events are diagnostics only (docs/LOGGING.md §5) — the audit trail stays authoritative

    /// <summary>SPEC rule 13: only public, non-hidden bookmarks can be reported; anything else is a 404 mask.</summary>
    public async Task<Report> ReportAsync(string reporter, Guid bookmarkId, ReportRequest request)
    {
        var bookmark = await db.Bookmarks.AsNoTracking().SingleOrDefaultAsync(b => b.Id == bookmarkId)
            ?? throw new NotFoundProblem();
        if (bookmark.Visibility != Visibility.Public || bookmark.Status != BookmarkStatus.Active)
        {
            throw new NotFoundProblem();
        }

        var reason = ValidatedReason(request);

        if (await db.Reports.AnyAsync(r => r.BookmarkId == bookmarkId && r.Reporter == reporter && r.Status == ReportStatus.Open))
        {
            throw new ConflictProblem("You already have an open report on this bookmark.");
        }
        var report = new Report
        {
            BookmarkId = bookmarkId,
            Reporter = reporter,
            Reason = reason,
            Comment = request.Comment,
            Status = ReportStatus.Open,
            CreatedAt = Clock.UtcNow(),
        };
        db.Reports.Add(report);
        try
        {
            await db.SaveChangesAsync();
        }
        catch (DbUpdateException e) when (e.InnerException is PostgresException
        {
            SqlState: PostgresErrorCodes.UniqueViolation,
            ConstraintName: "uq_reports_one_open_per_reporter",
        })
        {
            // lost the race against a concurrent report by the same user — same outcome as the pre-check
            throw new ConflictProblem("You already have an open report on this bookmark.");
        }
        catch (DbUpdateException e) when (e.InnerException is PostgresException
        {
            SqlState: PostgresErrorCodes.ForeignKeyViolation,
        })
        {
            // the bookmark vanished between the visibility check and the insert — the 404 mask still applies
            throw new NotFoundProblem();
        }
        logger.Event(LogLevel.Information, "report_created", "success", "Report created on a public bookmark",
            fields:
            [
                ("actor", reporter),
                ("resource_type", "report"),
                ("resource_id", report.Id.ToString()),
                ("bookmark_id", bookmarkId.ToString()),
                ("reason", Wire.Of(report.Reason)),
            ]);
        return report;
    }

    public async Task<(IReadOnlyList<Report> Items, long Total)> ListReportsAsync(ReportStatus status, int page, int size)
    {
        var filtered = db.Reports.AsNoTracking().Where(r => r.Status == status);
        var total = await filtered.LongCountAsync();
        var items = await filtered.OrderBy(r => r.CreatedAt).ThenBy(r => r.Id)
            .Skip(Paging.SkipOf(page, size)).Take(size).ToListAsync();
        return (items, total);
    }

    /// <summary>SPEC rule 13: the reporter's own feedback loop — their reports, newest first.</summary>
    public async Task<(IReadOnlyList<Report> Items, long Total)> ListMyReportsAsync(
        string reporter, ReportStatus? status, int page, int size)
    {
        var filtered = db.Reports.AsNoTracking().Where(r => r.Reporter == reporter);
        if (status is { } wanted)
        {
            filtered = filtered.Where(r => r.Status == wanted);
        }
        var total = await filtered.LongCountAsync();
        var items = await filtered.OrderByDescending(r => r.CreatedAt).ThenByDescending(r => r.Id)
            .Skip(Paging.SkipOf(page, size)).Take(size).ToListAsync();
        return (items, total);
    }

    /// <summary>SPEC rule 13: the reporter may revise reason/comment while the report is open.</summary>
    public async Task<Report> UpdateMyReportAsync(string reporter, Guid reportId, ReportRequest request)
    {
        await using var transaction = await db.Database.BeginTransactionAsync();
        var report = await OwnReportAsync(reporter, reportId);
        var reason = ValidatedReason(request);
        RequireOpen(report);
        report.Reason = reason;
        report.Comment = request.Comment;
        await db.SaveChangesAsync();
        await transaction.CommitAsync();
        logger.Event(LogLevel.Information, "report_updated", "success", "Report updated by its reporter",
            fields:
            [
                ("actor", reporter),
                ("resource_type", "report"),
                ("resource_id", report.Id.ToString()),
                ("bookmark_id", report.BookmarkId.ToString()),
                ("reason", Wire.Of(report.Reason)),
            ]);
        return report;
    }

    /// <summary>SPEC rule 13: withdrawing removes the report and frees the one-open-report slot.</summary>
    public async Task WithdrawAsync(string reporter, Guid reportId)
    {
        await using var transaction = await db.Database.BeginTransactionAsync();
        var report = await OwnReportAsync(reporter, reportId);
        RequireOpen(report);
        db.Reports.Remove(report);
        await db.SaveChangesAsync();
        await transaction.CommitAsync();
        logger.Event(LogLevel.Information, "report_withdrawn", "success", "Report withdrawn by its reporter",
            fields:
            [
                ("actor", reporter),
                ("resource_type", "report"),
                ("resource_id", report.Id.ToString()),
                ("bookmark_id", report.BookmarkId.ToString()),
            ]);
    }

    /// <summary>
    /// SPEC rule 14: `actioned` hides the bookmark and drags every sibling open
    /// report along. Decisions are revisable — any target status is accepted;
    /// `open` re-opens the report and clears the resolution fields. Moving away
    /// from `actioned` never restores the bookmark (rule 15 keeps hide/restore
    /// explicit).
    /// </summary>
    public async Task<Report> ResolveAsync(string actor, Guid reportId, ReportResolutionRequest request)
    {
        var validator = new Validator();
        var resolution = Wire.Parse<ReportStatus>(request.Resolution);
        if (resolution is null)
        {
            validator.Reject("resolution", "validation.resolution.invalid");
        }
        validator.Check((request.Note?.Length ?? 0) <= 1000, "note", "validation.resolution.note.too-long");
        validator.ThrowIfInvalid();

        await using var transaction = await db.Database.BeginTransactionAsync();
        var report = await ForUpdateAsync(reportId) ?? throw new NotFoundProblem();

        if (resolution == ReportStatus.Open)
        {
            ReopenOne(report, actor);
            await db.SaveChangesAsync();
            await transaction.CommitAsync();
            return report;
        }

        ResolveOne(report, resolution!.Value, actor, request.Note, autoResolved: false);

        if (resolution == ReportStatus.Actioned)
        {
            await HideBookmarkAsync(actor, report.BookmarkId, request.Note);
            var siblings = await db.Reports
                .Where(r => r.BookmarkId == report.BookmarkId && r.Status == ReportStatus.Open && r.Id != report.Id)
                .ToListAsync();
            foreach (var sibling in siblings)
            {
                ResolveOne(sibling, ReportStatus.Actioned, actor, request.Note, autoResolved: true);
            }
        }
        await db.SaveChangesAsync();
        await transaction.CommitAsync();
        return report;
    }

    /// <summary>SPEC rule 15: hide/restore switches `status` only; `visibility` is never touched.</summary>
    public async Task<Bookmark> SetBookmarkStatusAsync(string actor, Guid bookmarkId, BookmarkStatusRequest request)
    {
        var validator = new Validator();
        validator.Check(request.Status is not null, "status", "validation.bookmark-status.invalid");
        validator.Check((request.Note?.Length ?? 0) <= 1000, "note", "validation.bookmark-status.note.too-long");
        validator.ThrowIfInvalid();
        var status = request.Status!.Value;

        var bookmark = await db.Bookmarks.SingleOrDefaultAsync(b => b.Id == bookmarkId) ?? throw new NotFoundProblem();
        var previous = bookmark.Status;
        bookmark.Status = status;
        bookmark.UpdatedAt = Clock.UtcNow();
        auditService.Record(actor, "bookmark.status-changed", "bookmark", bookmark.Id.ToString(), new()
        {
            ["from"] = Wire.Of(previous),
            ["to"] = Wire.Of(status),
            ["note"] = request.Note,
        });
        await db.SaveChangesAsync();
        logger.Event(LogLevel.Information, "bookmark_status_changed", "success", "Bookmark moderation status changed",
            fields:
            [
                ("actor", actor),
                ("resource_type", "bookmark"),
                ("resource_id", bookmark.Id.ToString()),
                ("from", Wire.Of(previous)),
                ("to", Wire.Of(status)),
            ]);
        return bookmark;
    }

    /// <summary>
    /// Row-locked read for the mutating paths: reporter edit/withdraw and moderator
    /// resolution all check the status before acting, and without a lock the loser
    /// of a race flushes its stale snapshot over the winner's resolution (or deletes
    /// a just-resolved report).
    /// </summary>
    private Task<Report?> ForUpdateAsync(Guid reportId) =>
        db.Reports.FromSql($"select * from reports where id = {reportId} for update").SingleOrDefaultAsync();

    /// <summary>Someone else's report is a 404 mask — existence is not disclosed.</summary>
    private async Task<Report> OwnReportAsync(string reporter, Guid reportId)
    {
        var report = await ForUpdateAsync(reportId) ?? throw new NotFoundProblem();
        if (report.Reporter != reporter)
        {
            throw new NotFoundProblem();
        }
        return report;
    }

    private static void RequireOpen(Report report)
    {
        if (report.Status != ReportStatus.Open)
        {
            throw new ConflictProblem("The report has already been resolved.");
        }
    }

    private static ReportReason ValidatedReason(ReportRequest request)
    {
        var validator = new Validator();
        var reason = Wire.Parse<ReportReason>(request.Reason);
        if (reason is null)
        {
            validator.Reject("reason", "validation.report.reason.invalid");
        }
        validator.Check((request.Comment?.Length ?? 0) <= 1000, "comment", "validation.report.comment.too-long");
        validator.ThrowIfInvalid();
        return reason!.Value;
    }

    private void ReopenOne(Report report, string actor)
    {
        report.Status = ReportStatus.Open;
        report.ResolvedBy = null;
        report.ResolvedAt = null;
        report.ResolutionNote = null;
        auditService.Record(actor, "report.reopened", "report", report.Id.ToString(), new()
        {
            ["bookmarkId"] = report.BookmarkId.ToString(),
        });
        logger.Event(LogLevel.Information, "report_reopened", "success", "Report re-opened",
            fields:
            [
                ("actor", actor),
                ("resource_type", "report"),
                ("resource_id", report.Id.ToString()),
                ("bookmark_id", report.BookmarkId.ToString()),
            ]);
    }

    private void ResolveOne(Report report, ReportStatus resolution, string actor, string? note, bool autoResolved)
    {
        report.Status = resolution;
        report.ResolvedBy = actor;
        report.ResolvedAt = Clock.UtcNow();
        report.ResolutionNote = note;
        auditService.Record(actor, "report.resolved", "report", report.Id.ToString(), new()
        {
            ["bookmarkId"] = report.BookmarkId.ToString(),
            ["resolution"] = Wire.Of(resolution),
            ["note"] = note,
            ["autoResolved"] = autoResolved,
        });
        logger.Event(LogLevel.Information, "report_resolved", "success", "Report resolved",
            fields:
            [
                ("actor", actor),
                ("resource_type", "report"),
                ("resource_id", report.Id.ToString()),
                ("bookmark_id", report.BookmarkId.ToString()),
                ("resolution", Wire.Of(resolution)),
                ("auto_resolved", autoResolved),
            ]);
    }

    private async Task HideBookmarkAsync(string actor, Guid bookmarkId, string? note)
    {
        var bookmark = await db.Bookmarks.SingleOrDefaultAsync(b => b.Id == bookmarkId) ?? throw new NotFoundProblem();
        if (bookmark.Status != BookmarkStatus.Hidden)
        {
            bookmark.Status = BookmarkStatus.Hidden;
            bookmark.UpdatedAt = Clock.UtcNow();
            auditService.Record(actor, "bookmark.status-changed", "bookmark", bookmark.Id.ToString(), new()
            {
                ["from"] = Wire.Of(BookmarkStatus.Active),
                ["to"] = Wire.Of(BookmarkStatus.Hidden),
                ["note"] = note,
            });
            logger.Event(LogLevel.Information, "bookmark_status_changed", "success", "Bookmark hidden by an actioned report",
                fields:
                [
                    ("actor", actor),
                    ("resource_type", "bookmark"),
                    ("resource_id", bookmark.Id.ToString()),
                    ("from", Wire.Of(BookmarkStatus.Active)),
                    ("to", Wire.Of(BookmarkStatus.Hidden)),
                ]);
        }
    }
}
