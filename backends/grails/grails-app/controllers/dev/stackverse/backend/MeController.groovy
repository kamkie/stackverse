package dev.stackverse.backend

import dev.stackverse.backend.auth.AuthService
import dev.stackverse.backend.support.ControllerSupport

class MeController implements ControllerSupport {
    AuthService authService

    def show() {
        json(authService.requireUser())
    }
}
