package dev.stackverse.backend

import dev.stackverse.backend.account.UserAccountService
import dev.stackverse.backend.auth.AuthService
import dev.stackverse.backend.support.ControllerSupport
import dev.stackverse.backend.support.Paging

class AdminUserController implements ControllerSupport {
    AuthService authService
    UserAccountService userAccountService

    def list() {
        authService.requireRole("admin")
        json(userAccountService.list([q: params.q, status: params.status], Paging.page(params.page), Paging.size(params.size)))
    }

    def show(String username) {
        authService.requireRole("admin")
        json(userAccountService.require(username))
    }

    def setStatus(String username) {
        Map user = authService.requireRole("admin")
        json(userAccountService.setStatus(username, user.username, body()))
    }
}
