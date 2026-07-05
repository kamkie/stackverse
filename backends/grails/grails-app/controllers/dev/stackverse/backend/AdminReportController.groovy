package dev.stackverse.backend

import dev.stackverse.backend.auth.AuthService
import dev.stackverse.backend.moderation.ModerationService
import dev.stackverse.backend.support.ControllerSupport

class AdminReportController implements ControllerSupport {
    AuthService authService
    ModerationService moderationService

    def list() {
        authService.requireRole("moderator")
        json(moderationService.listQueue([page: params.page, size: params.size, status: params.status]))
    }

    def resolve(String id) {
        Map user = authService.requireRole("moderator")
        json(moderationService.resolve(uuid(id), body(), user.username, lang(), acceptLanguage()))
    }
}
