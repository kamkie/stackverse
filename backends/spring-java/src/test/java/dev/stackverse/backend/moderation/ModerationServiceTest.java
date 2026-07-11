package dev.stackverse.backend.moderation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.stackverse.backend.audit.AuditService;
import dev.stackverse.backend.bookmark.Bookmark;
import dev.stackverse.backend.bookmark.BookmarkRepository;
import dev.stackverse.backend.bookmark.BookmarkStatus;
import dev.stackverse.backend.bookmark.Visibility;
import dev.stackverse.backend.common.ConflictProblem;
import dev.stackverse.backend.common.FieldViolation;
import dev.stackverse.backend.common.NotFoundProblem;
import dev.stackverse.backend.common.ValidationProblem;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.dao.DataIntegrityViolationException;

class ModerationServiceTest {
    private final ReportRepository reportRepository = mock(ReportRepository.class);
    private final BookmarkRepository bookmarkRepository = mock(BookmarkRepository.class);
    private final AuditService auditService = mock(AuditService.class);
    private final ModerationService service = new ModerationService(reportRepository, bookmarkRepository, auditService);

    @Test
    void reportCreatesOpenFilingForPublicActiveBookmark() {
        Bookmark bookmark = bookmark(Visibility.PUBLIC, BookmarkStatus.ACTIVE);
        when(bookmarkRepository.findById(bookmark.getId())).thenReturn(Optional.of(bookmark));
        when(reportRepository.saveAndFlush(any(Report.class))).thenAnswer(invocation -> invocation.getArgument(0));

        Report report = service.report("reporter", bookmark.getId(), new ReportRequest("broken-link", "dead link"));

        assertThat(report.getBookmarkId()).isEqualTo(bookmark.getId());
        assertThat(report.getReporter()).isEqualTo("reporter");
        assertThat(report.getReason()).isEqualTo(ReportReason.BROKEN_LINK);
        assertThat(report.getComment()).isEqualTo("dead link");
        assertThat(report.getStatus()).isEqualTo(ReportStatus.OPEN);
        assertThat(report.getResolvedBy()).isNull();
        verify(reportRepository).existsByBookmarkIdAndReporterAndStatus(
            bookmark.getId(),
            "reporter",
            ReportStatus.OPEN
        );
        verify(reportRepository).saveAndFlush(report);
        verifyNoInteractions(auditService);
    }

    @Test
    void reportMasksPrivateHiddenAndMissingBookmarks() {
        Bookmark privateBookmark = bookmark(Visibility.PRIVATE, BookmarkStatus.ACTIVE);
        Bookmark hiddenBookmark = bookmark(Visibility.PUBLIC, BookmarkStatus.HIDDEN);
        UUID missing = UUID.randomUUID();
        when(bookmarkRepository.findById(privateBookmark.getId())).thenReturn(Optional.of(privateBookmark));
        when(bookmarkRepository.findById(hiddenBookmark.getId())).thenReturn(Optional.of(hiddenBookmark));
        when(bookmarkRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.report("reporter", privateBookmark.getId(), new ReportRequest("spam", null)))
            .isInstanceOf(NotFoundProblem.class);
        assertThatThrownBy(() -> service.report("reporter", hiddenBookmark.getId(), new ReportRequest("spam", null)))
            .isInstanceOf(NotFoundProblem.class);
        assertThatThrownBy(() -> service.report("reporter", missing, new ReportRequest("spam", null)))
            .isInstanceOf(NotFoundProblem.class);
        verifyNoInteractions(reportRepository);
    }

    @Test
    void reportMapsDuplicatePrecheckAndPersistenceRaceToConflict() {
        Bookmark bookmark = bookmark(Visibility.PUBLIC, BookmarkStatus.ACTIVE);
        when(bookmarkRepository.findById(bookmark.getId())).thenReturn(Optional.of(bookmark));
        when(reportRepository.existsByBookmarkIdAndReporterAndStatus(bookmark.getId(), "reporter", ReportStatus.OPEN))
            .thenReturn(true, false);

        assertThatThrownBy(() -> service.report("reporter", bookmark.getId(), new ReportRequest("spam", null)))
            .isInstanceOf(ConflictProblem.class)
            .hasMessageContaining("open report");

        when(reportRepository.saveAndFlush(any(Report.class)))
            .thenThrow(new DataIntegrityViolationException("unique open report"));
        assertThatThrownBy(() -> service.report("reporter", bookmark.getId(), new ReportRequest("spam", null)))
            .isInstanceOf(ConflictProblem.class)
            .hasMessageContaining("open report");
    }

    @Test
    void reportValidatesReasonAndCommentBeforePersistence() {
        Bookmark bookmark = bookmark(Visibility.PUBLIC, BookmarkStatus.ACTIVE);
        when(bookmarkRepository.findById(bookmark.getId())).thenReturn(Optional.of(bookmark));

        ValidationProblem problem = catchThrowableOfType(
            ValidationProblem.class,
            () -> service.report("reporter", bookmark.getId(), new ReportRequest("unknown", "x".repeat(1001)))
        );

        assertThat(problem.getViolations())
            .extracting(FieldViolation::field, FieldViolation::messageKey)
            .containsExactly(
                org.assertj.core.groups.Tuple.tuple("reason", "validation.report.reason.invalid"),
                org.assertj.core.groups.Tuple.tuple("comment", "validation.report.comment.too-long")
            );
        verify(reportRepository, never()).saveAndFlush(any());
    }

    @Test
    void reporterCanUpdateAndWithdrawOwnOpenReportWithoutAuditEntries() {
        Report report = report("reporter", ReportStatus.OPEN);
        when(reportRepository.findForUpdateById(report.getId())).thenReturn(report);

        Report updated = service.updateMyReport("reporter", report.getId(), new ReportRequest("other", "new context"));

        assertThat(updated).isSameAs(report);
        assertThat(updated.getReason()).isEqualTo(ReportReason.OTHER);
        assertThat(updated.getComment()).isEqualTo("new context");

        service.withdraw("reporter", report.getId());

        verify(reportRepository).delete(report);
        verifyNoInteractions(auditService);
    }

    @Test
    void reporterMutationMasksForeignReportAndRejectsResolvedReport() {
        Report foreign = report("someone-else", ReportStatus.OPEN);
        Report resolved = report("reporter", ReportStatus.DISMISSED);
        when(reportRepository.findForUpdateById(foreign.getId())).thenReturn(foreign);
        when(reportRepository.findForUpdateById(resolved.getId())).thenReturn(resolved);

        assertThatThrownBy(() -> service.updateMyReport("reporter", foreign.getId(), new ReportRequest("spam", null)))
            .isInstanceOf(NotFoundProblem.class);
        assertThatThrownBy(() -> service.withdraw("reporter", resolved.getId()))
            .isInstanceOf(ConflictProblem.class)
            .hasMessageContaining("resolved");
        verify(reportRepository, never()).delete(any());
    }

    @Test
    void dismissedResolutionSetsDispositionAndRecordsAudit() {
        Report report = report("reporter", ReportStatus.OPEN);
        when(reportRepository.findForUpdateById(report.getId())).thenReturn(report);

        Report resolved = service.resolve("moderator", report.getId(), new ReportResolutionRequest("dismissed", "not actionable"));

        assertThat(resolved.getStatus()).isEqualTo(ReportStatus.DISMISSED);
        assertThat(resolved.getResolvedBy()).isEqualTo("moderator");
        assertThat(resolved.getResolvedAt()).isNotNull();
        assertThat(resolved.getResolutionNote()).isEqualTo("not actionable");
        verify(auditService).record(
            eq("moderator"),
            eq("report.resolved"),
            eq("report"),
            eq(report.getId().toString()),
            org.mockito.ArgumentMatchers.argThat(detail ->
                detail.get("bookmarkId").equals(report.getBookmarkId().toString())
                    && detail.get("resolution").equals("dismissed")
                    && detail.get("autoResolved").equals(false)
            )
        );
        verifyNoInteractions(bookmarkRepository);
    }

    @Test
    void actionedResolutionLocksBookmarkFirstHidesItAndResolvesOpenSiblings() {
        Bookmark bookmark = bookmark(Visibility.PUBLIC, BookmarkStatus.ACTIVE);
        Report target = reportForBookmark(bookmark.getId(), "reporter", ReportStatus.OPEN);
        Report sibling = reportForBookmark(target.getBookmarkId(), "second-reporter", ReportStatus.OPEN);
        when(reportRepository.findBookmarkIdById(target.getId())).thenReturn(target.getBookmarkId());
        when(bookmarkRepository.findForUpdateById(target.getBookmarkId())).thenReturn(bookmark);
        when(reportRepository.findForUpdateById(target.getId())).thenReturn(target);
        when(bookmarkRepository.findById(target.getBookmarkId())).thenReturn(Optional.of(bookmark));
        when(reportRepository.findForUpdateByBookmarkIdAndStatusOrderByIdAsc(target.getBookmarkId(), ReportStatus.OPEN))
            .thenReturn(List.of(target, sibling));

        service.resolve("moderator", target.getId(), new ReportResolutionRequest("actioned", "confirmed"));

        InOrder locks = inOrder(reportRepository, bookmarkRepository);
        locks.verify(reportRepository).findBookmarkIdById(target.getId());
        locks.verify(bookmarkRepository).findForUpdateById(target.getBookmarkId());
        locks.verify(reportRepository).findForUpdateById(target.getId());
        assertThat(target.getStatus()).isEqualTo(ReportStatus.ACTIONED);
        assertThat(sibling.getStatus()).isEqualTo(ReportStatus.ACTIONED);
        assertThat(sibling.getResolvedBy()).isEqualTo("moderator");
        assertThat(sibling.getResolutionNote()).isEqualTo("confirmed");
        assertThat(bookmark.getStatus()).isEqualTo(BookmarkStatus.HIDDEN);
        assertThat(bookmark.getUpdatedAt()).isAfter(Instant.EPOCH);
        verify(auditService, times(2)).record(
            eq("moderator"), eq("report.resolved"), eq("report"), any(), anyMap()
        );
        verify(auditService).record(
            eq("moderator"), eq("bookmark.status-changed"), eq("bookmark"), eq(bookmark.getId().toString()), anyMap()
        );
    }

    @Test
    void actionedResolutionMasksMissingReportBeforeTakingReportLock() {
        UUID reportId = UUID.randomUUID();
        when(reportRepository.findBookmarkIdById(reportId)).thenReturn(null);

        assertThatThrownBy(() -> service.resolve("moderator", reportId, new ReportResolutionRequest("actioned", null)))
            .isInstanceOf(NotFoundProblem.class);

        verify(reportRepository, never()).findForUpdateById(reportId);
        verifyNoInteractions(bookmarkRepository);
    }

    @Test
    void reopenClearsResolutionFieldsIgnoresNoteAndRecordsAudit() {
        Report report = resolvedReport("reporter", ReportStatus.DISMISSED);
        when(reportRepository.findForUpdateById(report.getId())).thenReturn(report);

        Report reopened = service.resolve("moderator", report.getId(), new ReportResolutionRequest("open", "ignored"));

        assertThat(reopened.getStatus()).isEqualTo(ReportStatus.OPEN);
        assertThat(reopened.getResolvedBy()).isNull();
        assertThat(reopened.getResolvedAt()).isNull();
        assertThat(reopened.getResolutionNote()).isNull();
        verify(reportRepository).existsByBookmarkIdAndReporterAndStatusAndIdNot(
            report.getBookmarkId(), report.getReporter(), ReportStatus.OPEN, report.getId()
        );
        verify(reportRepository).flush();
        verify(auditService).record(
            "moderator",
            "report.reopened",
            "report",
            report.getId().toString(),
            Map.of("bookmarkId", report.getBookmarkId().toString())
        );
    }

    @Test
    void reopenMapsExistingSiblingAndUniqueRaceToConflict() {
        Report report = resolvedReport("reporter", ReportStatus.DISMISSED);
        when(reportRepository.findForUpdateById(report.getId())).thenReturn(report);
        when(reportRepository.existsByBookmarkIdAndReporterAndStatusAndIdNot(
            report.getBookmarkId(), report.getReporter(), ReportStatus.OPEN, report.getId()
        )).thenReturn(true, false);

        assertThatThrownBy(() -> service.resolve("moderator", report.getId(), new ReportResolutionRequest("open", null)))
            .isInstanceOf(ConflictProblem.class)
            .hasMessageContaining("another open report");

        doThrow(new DataIntegrityViolationException("unique open report")).when(reportRepository).flush();
        assertThatThrownBy(() -> service.resolve("moderator", report.getId(), new ReportResolutionRequest("open", null)))
            .isInstanceOf(ConflictProblem.class)
            .hasMessageContaining("another open report");
    }

    @Test
    void setBookmarkStatusMutatesLockedRowAndRecordsTransition() {
        Bookmark bookmark = bookmark(Visibility.PUBLIC, BookmarkStatus.ACTIVE);
        when(bookmarkRepository.findForUpdateById(bookmark.getId())).thenReturn(bookmark);

        Bookmark hidden = service.setBookmarkStatus(
            "moderator",
            bookmark.getId(),
            new BookmarkStatusRequest(BookmarkStatus.HIDDEN, "policy")
        );

        assertThat(hidden.getStatus()).isEqualTo(BookmarkStatus.HIDDEN);
        assertThat(hidden.getUpdatedAt()).isAfter(Instant.EPOCH);
        verify(auditService).record(
            eq("moderator"),
            eq("bookmark.status-changed"),
            eq("bookmark"),
            eq(bookmark.getId().toString()),
            org.mockito.ArgumentMatchers.argThat(detail ->
                detail.get("from").equals("active")
                    && detail.get("to").equals("hidden")
                    && detail.get("note").equals("policy")
            )
        );
    }

    @Test
    void resolutionAndBookmarkStatusValidateBeforePersistence() {
        ValidationProblem resolution = catchThrowableOfType(
            ValidationProblem.class,
            () -> service.resolve("moderator", UUID.randomUUID(), new ReportResolutionRequest("unknown", "x".repeat(1001)))
        );
        ValidationProblem bookmarkStatus = catchThrowableOfType(
            ValidationProblem.class,
            () -> service.setBookmarkStatus("moderator", UUID.randomUUID(), new BookmarkStatusRequest(null, "x".repeat(1001)))
        );

        assertThat(resolution.getViolations())
            .extracting(FieldViolation::messageKey)
            .containsExactly(
                "validation.resolution.invalid",
                "validation.resolution.note.too-long"
            );
        assertThat(bookmarkStatus.getViolations())
            .extracting(FieldViolation::messageKey)
            .containsExactly(
                "validation.bookmark-status.invalid",
                "validation.bookmark-status.note.too-long"
            );
        verifyNoInteractions(reportRepository, bookmarkRepository, auditService);
    }

    @Test
    void setBookmarkStatusMasksMissingBookmark() {
        UUID bookmarkId = UUID.randomUUID();
        when(bookmarkRepository.findForUpdateById(bookmarkId)).thenReturn(null);

        assertThatThrownBy(() -> service.setBookmarkStatus(
            "moderator",
            bookmarkId,
            new BookmarkStatusRequest(BookmarkStatus.ACTIVE, null)
        )).isInstanceOf(NotFoundProblem.class);
    }

    private static Bookmark bookmark(Visibility visibility, BookmarkStatus status) {
        return new Bookmark(
            "owner",
            "https://example.com",
            "Example",
            null,
            Set.of("java"),
            visibility,
            status,
            Instant.EPOCH,
            Instant.EPOCH
        );
    }

    private static Report report(String reporter, ReportStatus status) {
        return reportForBookmark(UUID.randomUUID(), reporter, status);
    }

    private static Report reportForBookmark(UUID bookmarkId, String reporter, ReportStatus status) {
        return new Report(
            bookmarkId,
            reporter,
            ReportReason.SPAM,
            "context",
            status,
            null,
            null,
            null,
            Instant.EPOCH
        );
    }

    private static Report resolvedReport(String reporter, ReportStatus status) {
        return new Report(
            UUID.randomUUID(),
            reporter,
            ReportReason.SPAM,
            "context",
            status,
            "moderator",
            Instant.parse("2026-07-01T00:00:00Z"),
            "old note",
            Instant.EPOCH
        );
    }

}
