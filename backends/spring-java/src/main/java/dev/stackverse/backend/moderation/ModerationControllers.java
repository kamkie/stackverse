package dev.stackverse.backend.moderation;

import static dev.stackverse.backend.common.RequestValidation.requireValidPaging;

import dev.stackverse.backend.bookmark.BookmarkResponse;
import dev.stackverse.backend.common.PageResponse;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
class ReportController {
    private final ModerationService service;

    ReportController(ModerationService service) {
        this.service = service;
    }

    @PostMapping("/api/v1/bookmarks/{id}/reports")
    ResponseEntity<ReportResponse> report(
        @PathVariable("id") UUID id,
        @RequestBody ReportRequest request,
        Authentication authentication
    ) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ReportResponse.of(service.report(authentication.getName(), id, request)));
    }

    @GetMapping("/api/v1/reports")
    PageResponse<ReportResponse> listMine(
        @RequestParam(name = "status", required = false) ReportStatus status,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size,
        Authentication authentication
    ) {
        requireValidPaging(page, size);
        return PageResponse.of(service.listMyReports(authentication.getName(), status, page, size), ReportResponse::of);
    }

    @PutMapping("/api/v1/reports/{id}")
    ReportResponse updateMine(
        @PathVariable("id") UUID id,
        @RequestBody ReportRequest request,
        Authentication authentication
    ) {
        return ReportResponse.of(service.updateMyReport(authentication.getName(), id, request));
    }

    @DeleteMapping("/api/v1/reports/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void withdrawMine(@PathVariable("id") UUID id, Authentication authentication) {
        service.withdraw(authentication.getName(), id);
    }
}

@RestController
@PreAuthorize("hasRole('moderator')")
class AdminModerationController {
    private final ModerationService service;

    AdminModerationController(ModerationService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/admin/reports")
    PageResponse<ReportResponse> listReports(
        @RequestParam(name = "status", defaultValue = "open") ReportStatus status,
        @RequestParam(name = "page", defaultValue = "0") int page,
        @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        requireValidPaging(page, size);
        return PageResponse.of(service.listReports(status, page, size), ReportResponse::of);
    }

    @PutMapping("/api/v1/admin/reports/{id}")
    ReportResponse resolveReport(
        @PathVariable("id") UUID id,
        @RequestBody ReportResolutionRequest request,
        Authentication authentication
    ) {
        return ReportResponse.of(service.resolve(authentication.getName(), id, request));
    }

    @PutMapping("/api/v1/admin/bookmarks/{id}/status")
    BookmarkResponse setBookmarkStatus(
        @PathVariable("id") UUID id,
        @RequestBody BookmarkStatusRequest request,
        Authentication authentication
    ) {
        return BookmarkResponse.of(service.setBookmarkStatus(authentication.getName(), id, request));
    }
}
