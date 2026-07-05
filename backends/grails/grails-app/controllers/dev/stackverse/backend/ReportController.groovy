package dev.stackverse.backend

import dev.stackverse.backend.auth.AuthService
import dev.stackverse.backend.moderation.ModerationService
import dev.stackverse.backend.support.ControllerSupport

class ReportController implements ControllerSupport {
    AuthService authService
    ModerationService moderationService

    def listMine() {
        Map user = authService.requireUser()
        json(moderationService.listMine(user.username, [page: params.page, size: params.size, status: params.status]))
    }

    def updateMine(String id) {
        Map user = authService.requireUser()
        json(moderationService.updateMine(uuid(id), body(), user.username, lang(), acceptLanguage()))
    }

    def withdraw(String id) {
        Map user = authService.requireUser()
        moderationService.withdraw(uuid(id), user.username)
        noContent()
    }
}
