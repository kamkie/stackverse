package dev.stackverse.backend.stats;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@PreAuthorize("hasRole('moderator')")
public class AdminStatsController {
    private final StatsService service;

    public AdminStatsController(StatsService service) {
        this.service = service;
    }

    @GetMapping("/api/v1/admin/stats")
    public ResponseEntity<AdminStatsResponse> stats() {
        return ResponseEntity.ok().cacheControl(CacheControl.noCache()).body(service.stats());
    }
}
