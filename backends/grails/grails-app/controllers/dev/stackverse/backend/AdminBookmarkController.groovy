package dev.stackverse.backend

import dev.stackverse.backend.auth.AuthService
import dev.stackverse.backend.moderation.ModerationService
import dev.stackverse.backend.support.ControllerSupport

class AdminBookmarkController implements ControllerSupport {
    AuthService authService
    ModerationService moderationService

    def setStatus(String id) {
        Map user = authService.requireRole("moderator")
        json(moderationService.setBookmarkStatus(uuid(id), body(), user.username, lang(), acceptLanguage()))
    }
}
