package dev.stackverse.backend.moderation;

import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface ReportRepository extends JpaRepository<Report, UUID> {
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    Report findForUpdateById(UUID id);

    @Query("select r.bookmarkId from Report r where r.id = :id")
    UUID findBookmarkIdById(@Param("id") UUID id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    List<Report> findForUpdateByBookmarkIdAndStatusOrderByIdAsc(UUID bookmarkId, ReportStatus status);

    boolean existsByBookmarkIdAndReporterAndStatus(UUID bookmarkId, String reporter, ReportStatus status);

    boolean existsByBookmarkIdAndReporterAndStatusAndIdNot(UUID bookmarkId, String reporter, ReportStatus status, UUID id);

    Page<Report> findByStatus(ReportStatus status, Pageable pageable);

    Page<Report> findByReporter(String reporter, Pageable pageable);

    Page<Report> findByReporterAndStatus(String reporter, ReportStatus status, Pageable pageable);

    long countByStatus(ReportStatus status);
}
