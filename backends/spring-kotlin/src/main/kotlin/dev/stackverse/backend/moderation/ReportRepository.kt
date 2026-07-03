package dev.stackverse.backend.moderation

import jakarta.persistence.LockModeType
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
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

    fun existsByBookmarkIdAndReporterAndStatus(bookmarkId: UUID, reporter: String, status: ReportStatus): Boolean

    fun findByStatus(status: ReportStatus, pageable: Pageable): Page<Report>

    fun findByReporter(reporter: String, pageable: Pageable): Page<Report>

    fun findByReporterAndStatus(reporter: String, status: ReportStatus, pageable: Pageable): Page<Report>

    fun findByBookmarkIdAndStatus(bookmarkId: UUID, status: ReportStatus): List<Report>

    fun countByStatus(status: ReportStatus): Long
}
