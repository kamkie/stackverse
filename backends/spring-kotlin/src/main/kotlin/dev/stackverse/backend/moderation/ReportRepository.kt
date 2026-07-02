package dev.stackverse.backend.moderation

import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import java.util.UUID

interface ReportRepository : JpaRepository<Report, UUID> {

    fun existsByBookmarkIdAndReporterAndStatus(bookmarkId: UUID, reporter: String, status: ReportStatus): Boolean

    fun findByStatus(status: ReportStatus, pageable: Pageable): Page<Report>

    fun findByBookmarkIdAndStatus(bookmarkId: UUID, status: ReportStatus): List<Report>

    fun countByStatus(status: ReportStatus): Long
}
