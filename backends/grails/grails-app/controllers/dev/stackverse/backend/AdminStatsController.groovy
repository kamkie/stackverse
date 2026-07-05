package dev.stackverse.backend

import dev.stackverse.backend.auth.AuthService
import dev.stackverse.backend.stats.StatsService
import dev.stackverse.backend.support.ControllerSupport

class AdminStatsController implements ControllerSupport {
    AuthService authService
    StatsService statsService

    def show() {
        authService.requireRole("moderator")
        cached(statsService.snapshot())
    }
}
