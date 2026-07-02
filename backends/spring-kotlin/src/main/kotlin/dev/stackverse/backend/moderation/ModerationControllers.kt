package dev.stackverse.backend.moderation

import dev.stackverse.backend.bookmark.BookmarkResponse
import dev.stackverse.backend.common.PageResponse
import dev.stackverse.backend.common.requireValidPaging
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.access.prepost.PreAuthorize
import org.springframework.security.core.Authentication
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
class ReportController(private val service: ModerationService) {

    @PostMapping("/api/v1/bookmarks/{id}/reports")
    fun report(
        @PathVariable id: UUID,
        @RequestBody request: ReportRequest,
        authentication: Authentication,
    ): ResponseEntity<ReportResponse> {
        val report = service.report(authentication.name, id, request)
        return ResponseEntity.status(HttpStatus.CREATED).body(ReportResponse.of(report))
    }
}

@RestController
@PreAuthorize("hasRole('moderator')")
class AdminModerationController(private val service: ModerationService) {

    @GetMapping("/api/v1/admin/reports")
    fun listReports(
        @RequestParam(defaultValue = "open") status: ReportStatus,
        @RequestParam(defaultValue = "0") page: Int,
        @RequestParam(defaultValue = "20") size: Int,
    ): PageResponse<ReportResponse> {
        requireValidPaging(page, size)
        return PageResponse.of(service.listReports(status, page, size)) { ReportResponse.of(it) }
    }

    @PutMapping("/api/v1/admin/reports/{id}")
    fun resolveReport(
        @PathVariable id: UUID,
        @RequestBody request: ReportResolutionRequest,
        authentication: Authentication,
    ): ReportResponse = ReportResponse.of(service.resolve(authentication.name, id, request))

    @PutMapping("/api/v1/admin/bookmarks/{id}/status")
    fun setBookmarkStatus(
        @PathVariable id: UUID,
        @RequestBody request: BookmarkStatusRequest,
        authentication: Authentication,
    ): BookmarkResponse = BookmarkResponse.of(service.setBookmarkStatus(authentication.name, id, request))
}
