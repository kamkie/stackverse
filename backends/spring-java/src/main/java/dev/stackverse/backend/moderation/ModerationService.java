package dev.stackverse.backend.moderation;

import static dev.stackverse.backend.common.Time.nowUtc;

import dev.stackverse.backend.audit.AuditService;
import dev.stackverse.backend.bookmark.Bookmark;
import dev.stackverse.backend.bookmark.BookmarkRepository;
import dev.stackverse.backend.bookmark.BookmarkStatus;
import dev.stackverse.backend.bookmark.Visibility;
import dev.stackverse.backend.common.ConflictProblem;
import dev.stackverse.backend.common.Logging;
import dev.stackverse.backend.common.NotFoundProblem;
import dev.stackverse.backend.common.Validator;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class ModerationService {
    private final ReportRepository reportRepository;
    private final BookmarkRepository bookmarkRepository;
    private final AuditService auditService;
    private final org.slf4j.Logger log = LoggerFactory.getLogger(getClass());

    public ModerationService(ReportRepository reportRepository, BookmarkRepository bookmarkRepository, AuditService auditService) {
        this.reportRepository = reportRepository;
        this.bookmarkRepository = bookmarkRepository;
        this.auditService = auditService;
    }

    /** SPEC rule 13: only public, active bookmarks can be reported; everything else is a 404 mask. */
    public Report report(String reporter, UUID bookmarkId, ReportRequest request) {
        Bookmark bookmark = bookmarkRepository.findById(bookmarkId).orElseThrow(NotFoundProblem::new);
        if (bookmark.getVisibility() != Visibility.PUBLIC || bookmark.getStatus() != BookmarkStatus.ACTIVE) {
            throw new NotFoundProblem();
        }
        ReportReason reason = validatedReason(request);
        if (reportRepository.existsByBookmarkIdAndReporterAndStatus(bookmarkId, reporter, ReportStatus.OPEN)) {
            throw new ConflictProblem("You already have an open report on this bookmark.");
        }
        try {
            Report report = reportRepository.saveAndFlush(new Report(
                bookmarkId,
                reporter,
                reason,
                request.comment(),
                ReportStatus.OPEN,
                null,
                null,
                null,
                nowUtc()
            ));
            Logging.logEvent(log, Level.INFO, "report_created", "success", "Report created on a public bookmark",
                "actor", reporter, "resource_type", "report", "resource_id", report.getId().toString(),
                "bookmark_id", bookmarkId.toString(), "reason", report.getReason().getWire());
            return report;
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictProblem("You already have an open report on this bookmark.");
        }
    }

    @Transactional(readOnly = true)
    public Page<Report> listReports(ReportStatus status, int page, int size) {
        return reportRepository.findByStatus(status, PageRequest.of(page, size, Sort.by("createdAt", "id")));
    }

    @Transactional(readOnly = true)
    public Page<Report> listMyReports(String reporter, ReportStatus status, int page, int size) {
        var pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt", "id"));
        return status == null
            ? reportRepository.findByReporter(reporter, pageable)
            : reportRepository.findByReporterAndStatus(reporter, status, pageable);
    }

    public Report updateMyReport(String reporter, UUID reportId, ReportRequest request) {
        Report report = ownReport(reporter, reportId);
        ReportReason reason = validatedReason(request);
        requireOpen(report);
        report.setReason(reason);
        report.setComment(request.comment());
        Logging.logEvent(log, Level.INFO, "report_updated", "success", "Report updated by its reporter",
            "actor", reporter, "resource_type", "report", "resource_id", report.getId().toString(),
            "bookmark_id", report.getBookmarkId().toString(), "reason", report.getReason().getWire());
        return report;
    }

    public void withdraw(String reporter, UUID reportId) {
        Report report = ownReport(reporter, reportId);
        requireOpen(report);
        reportRepository.delete(report);
        Logging.logEvent(log, Level.INFO, "report_withdrawn", "success", "Report withdrawn by its reporter",
            "actor", reporter, "resource_type", "report", "resource_id", report.getId().toString(),
            "bookmark_id", report.getBookmarkId().toString());
    }

    public Report resolve(String actor, UUID reportId, ReportResolutionRequest request) {
        Validator validator = new Validator();
        ReportStatus resolution = switch (request.resolution() == null ? "" : request.resolution()) {
            case "open" -> ReportStatus.OPEN;
            case "dismissed" -> ReportStatus.DISMISSED;
            case "actioned" -> ReportStatus.ACTIONED;
            default -> {
                validator.reject("resolution", "validation.resolution.invalid");
                yield null;
            }
        };
        validator.check(request.note() == null || request.note().length() <= 1000, "note", "validation.resolution.note.too-long");
        validator.throwIfInvalid();

        if (resolution == ReportStatus.ACTIONED) {
            UUID bookmarkId = reportRepository.findBookmarkIdById(reportId);
            if (bookmarkId == null) {
                throw new NotFoundProblem();
            }
            bookmarkRepository.findForUpdateById(bookmarkId);
        }
        Report report = reportRepository.findForUpdateById(reportId);
        if (report == null) {
            throw new NotFoundProblem();
        }

        if (resolution == ReportStatus.OPEN) {
            reopenOne(report, actor);
            return report;
        }

        resolveOne(report, resolution, actor, request.note(), false);

        if (resolution == ReportStatus.ACTIONED) {
            hideBookmark(actor, report.getBookmarkId(), request.note());
            reportRepository.findForUpdateByBookmarkIdAndStatusOrderByIdAsc(report.getBookmarkId(), ReportStatus.OPEN).stream()
                .filter(sibling -> !sibling.getId().equals(report.getId()))
                .forEach(sibling -> resolveOne(sibling, ReportStatus.ACTIONED, actor, request.note(), true));
        }
        return report;
    }

    public Bookmark setBookmarkStatus(String actor, UUID bookmarkId, BookmarkStatusRequest request) {
        Validator validator = new Validator();
        validator.check(request.status() != null, "status", "validation.bookmark-status.invalid");
        validator.check(request.note() == null || request.note().length() <= 1000, "note", "validation.bookmark-status.note.too-long");
        validator.throwIfInvalid();

        Bookmark bookmark = bookmarkRepository.findForUpdateById(bookmarkId);
        if (bookmark == null) {
            throw new NotFoundProblem();
        }
        BookmarkStatus previous = bookmark.getStatus();
        bookmark.setStatus(request.status());
        bookmark.setUpdatedAt(nowUtc());
        auditService.record(actor, "bookmark.status-changed", "bookmark", bookmark.getId().toString(),
            detail("from", previous.getWire(), "to", request.status().getWire(), "note", request.note()));
        Logging.logEvent(log, Level.INFO, "bookmark_status_changed", "success", "Bookmark moderation status changed",
            "actor", actor, "resource_type", "bookmark", "resource_id", bookmark.getId().toString(),
            "from", previous.getWire(), "to", request.status().getWire());
        return bookmark;
    }

    private Report ownReport(String reporter, UUID reportId) {
        Report report = reportRepository.findForUpdateById(reportId);
        if (report == null || !report.getReporter().equals(reporter)) {
            throw new NotFoundProblem();
        }
        return report;
    }

    private void requireOpen(Report report) {
        if (report.getStatus() != ReportStatus.OPEN) {
            throw new ConflictProblem("The report has already been resolved.");
        }
    }

    private ReportReason validatedReason(ReportRequest request) {
        Validator validator = new Validator();
        ReportReason reason = ReportReason.fromWire(request.reason()).orElse(null);
        if (reason == null) {
            validator.reject("reason", "validation.report.reason.invalid");
        }
        validator.check(request.comment() == null || request.comment().length() <= 1000, "comment", "validation.report.comment.too-long");
        validator.throwIfInvalid();
        return reason;
    }

    private void reopenOne(Report report, String actor) {
        if (reportRepository.existsByBookmarkIdAndReporterAndStatusAndIdNot(
            report.getBookmarkId(),
            report.getReporter(),
            ReportStatus.OPEN,
            report.getId()
        )) {
            throw new ConflictProblem("The reporter already has another open report on this bookmark.");
        }
        report.setStatus(ReportStatus.OPEN);
        report.setResolvedBy(null);
        report.setResolvedAt(null);
        report.setResolutionNote(null);
        try {
            reportRepository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw new ConflictProblem("The reporter already has another open report on this bookmark.");
        }
        auditService.record(actor, "report.reopened", "report", report.getId().toString(),
            Map.of("bookmarkId", report.getBookmarkId().toString()));
        Logging.logEvent(log, Level.INFO, "report_reopened", "success", "Report re-opened",
            "actor", actor, "resource_type", "report", "resource_id", report.getId().toString(),
            "bookmark_id", report.getBookmarkId().toString());
    }

    private void resolveOne(Report report, ReportStatus resolution, String actor, String note, boolean autoResolved) {
        report.setStatus(resolution);
        report.setResolvedBy(actor);
        report.setResolvedAt(nowUtc());
        report.setResolutionNote(note);
        auditService.record(actor, "report.resolved", "report", report.getId().toString(),
            detail(
                "bookmarkId", report.getBookmarkId().toString(),
                "resolution", resolution.getWire(),
                "note", note,
                "autoResolved", autoResolved
            ));
        Logging.logEvent(log, Level.INFO, "report_resolved", "success", "Report resolved",
            "actor", actor, "resource_type", "report", "resource_id", report.getId().toString(),
            "bookmark_id", report.getBookmarkId().toString(), "resolution", resolution.getWire(),
            "auto_resolved", autoResolved);
    }

    private void hideBookmark(String actor, UUID bookmarkId, String note) {
        Bookmark bookmark = bookmarkRepository.findById(bookmarkId).orElseThrow(NotFoundProblem::new);
        if (bookmark.getStatus() != BookmarkStatus.HIDDEN) {
            bookmark.setStatus(BookmarkStatus.HIDDEN);
            bookmark.setUpdatedAt(nowUtc());
            auditService.record(actor, "bookmark.status-changed", "bookmark", bookmark.getId().toString(),
                detail("from", BookmarkStatus.ACTIVE.getWire(), "to", BookmarkStatus.HIDDEN.getWire(), "note", note));
            Logging.logEvent(log, Level.INFO, "bookmark_status_changed", "success", "Bookmark hidden by an actioned report",
                "actor", actor, "resource_type", "bookmark", "resource_id", bookmark.getId().toString(),
            "from", BookmarkStatus.ACTIVE.getWire(), "to", BookmarkStatus.HIDDEN.getWire());
        }
    }

    private static Map<String, Object> detail(Object... fields) {
        Map<String, Object> detail = new LinkedHashMap<>();
        for (int i = 0; i + 1 < fields.length; i += 2) {
            detail.put(String.valueOf(fields[i]), fields[i + 1]);
        }
        return detail;
    }
}
