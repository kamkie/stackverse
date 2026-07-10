package dev.stackverse.backend

import dev.stackverse.backend.auth.AuthService
import dev.stackverse.backend.command.ResolutionCommand
import dev.stackverse.backend.message.MessageService
import dev.stackverse.backend.moderation.ModerationService
import dev.stackverse.backend.support.ControllerSupport

class AdminReportController implements ControllerSupport {
    AuthService authService
    MessageService messageService
    ModerationService moderationService

    def list() {
        authService.requireRole("moderator")
        json(moderationService.listQueue([page: params.page, size: params.size, status: params.status]))
    }

    def resolve(String id, ResolutionCommand command) {
        Map user = authService.requireRole("moderator")
        json(moderationService.resolve(uuid(id), command.validated(messageService, lang(), acceptLanguage()),
            user.username))
    }
}
