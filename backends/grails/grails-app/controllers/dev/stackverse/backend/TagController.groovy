package dev.stackverse.backend

import dev.stackverse.backend.auth.AuthService
import dev.stackverse.backend.bookmark.BookmarkService
import dev.stackverse.backend.support.ControllerSupport

class TagController implements ControllerSupport {
    AuthService authService
    BookmarkService bookmarkService

    def list() {
        Map user = authService.requireUser()
        json(bookmarkService.tags(user.username))
    }
}
