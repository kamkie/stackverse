package dev.stackverse.backend

import dev.stackverse.backend.auth.AuthService
import dev.stackverse.backend.command.ReportCommand
import dev.stackverse.backend.message.MessageService
import dev.stackverse.backend.moderation.ModerationService
import dev.stackverse.backend.support.ControllerSupport

class ReportController implements ControllerSupport {
    AuthService authService
    MessageService messageService
    ModerationService moderationService

    def listMine() {
        Map user = authService.requireUser()
        json(moderationService.listMine(user.username, [page: params.page, size: params.size, status: params.status]))
    }

    def updateMine(String id, ReportCommand command) {
        Map user = authService.requireUser()
        json(moderationService.updateMine(uuid(id), command.validated(messageService, lang(), acceptLanguage()),
            user.username))
    }

    def withdraw(String id) {
        Map user = authService.requireUser()
        moderationService.withdraw(uuid(id), user.username)
        noContent()
    }
}
