package dev.stackverse.backend

import dev.stackverse.backend.auth.AuthService
import dev.stackverse.backend.command.BookmarkStatusCommand
import dev.stackverse.backend.message.MessageService
import dev.stackverse.backend.moderation.ModerationService
import dev.stackverse.backend.support.ControllerSupport

class AdminBookmarkController implements ControllerSupport {
    AuthService authService
    MessageService messageService
    ModerationService moderationService

    def setStatus(String id, BookmarkStatusCommand command) {
        Map user = authService.requireRole("moderator")
        json(moderationService.setBookmarkStatus(uuid(id), command.validated(messageService, lang(), acceptLanguage()),
            user.username))
    }
}
