package dev.stackverse.backend

import dev.stackverse.backend.auth.AuthService
import dev.stackverse.backend.message.MessageService
import dev.stackverse.backend.support.ControllerSupport

class MessageController implements ControllerSupport {
    AuthService authService
    MessageService messageService

    def list() {
        cached(messageService.list(params))
    }

    def bundle() {
        Map bundle = messageService.bundle(params.lang, acceptLanguage())
        cached(bundle, ["Content-Language": bundle.language])
    }

    def show(String id) {
        cached(messageService.get(uuid(id)))
    }

    def create() {
        Map user = authService.requireRole("admin")
        Map created = messageService.create(body(), user.username, lang(), acceptLanguage())
        json(created, 201, ["Location": "/api/v1/messages/${created.id}"])
    }

    def update(String id) {
        Map user = authService.requireRole("admin")
        json(messageService.update(uuid(id), body(), user.username, lang(), acceptLanguage()))
    }

    def delete(String id) {
        Map user = authService.requireRole("admin")
        messageService.delete(uuid(id), user.username)
        noContent()
    }
}
