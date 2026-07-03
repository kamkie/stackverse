package dev.stackverse.backend.moderation

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import java.util.UUID

interface ReportRepository : JpaRepository<Report, UUID> {

    /**
     * Row-locked read for the mutating paths: reporter edit/withdraw and
     * moderator resolution all check the status before acting, and without a
     * lock the loser of a race flushes its stale snapshot over the winner's
     * resolution (or deletes a just-resolved report).
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateById(id: UUID): Report?

    /**
     * Scalar lookup that keeps the entity out of the persistence context, so
     * the `actioned` path can learn which bookmark to lock before taking any
     * row locks (see the lock-order note on [ModerationService.resolve]).
     */
    @Query("select r.bookmarkId from Report r where r.id = :id")
    fun findBookmarkIdById(id: UUID): UUID?

    /**
     * Locked sibling read for auto-resolution: FOR UPDATE re-checks the status
     * after the lock is granted, so a report resolved by a concurrent
     * transaction drops out instead of being blindly overwritten.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    fun findForUpdateByBookmarkIdAndStatusOrderByIdAsc(bookmarkId: UUID, status: ReportStatus): List<Report>

    fun existsByBookmarkIdAndReporterAndStatus(bookmarkId: UUID, reporter: String, status: ReportStatus): Boolean

    /** Guards re-opening: is there another report (≠ this one) already open for the pair? */
    fun existsByBookmarkIdAndReporterAndStatusAndIdNot(
        bookmarkId: UUID,
        reporter: String,
        status: ReportStatus,
        id: UUID,
    ): Boolean

    fun findByStatus(status: ReportStatus, pageable: Pageable): Page<Report>

    fun findByReporter(reporter: String, pageable: Pageable): Page<Report>

    fun findByReporterAndStatus(reporter: String, status: ReportStatus, pageable: Pageable): Page<Report>

    fun countByStatus(status: ReportStatus): Long
}
