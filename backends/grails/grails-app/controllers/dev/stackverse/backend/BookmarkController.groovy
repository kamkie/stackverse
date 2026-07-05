package dev.stackverse.backend

import dev.stackverse.backend.auth.AuthService
import dev.stackverse.backend.bookmark.BookmarkService
import dev.stackverse.backend.support.ControllerSupport

class BookmarkController implements ControllerSupport {
    AuthService authService
    BookmarkService bookmarkService

    def listV1() {
        Map user = authService.currentUser()
        Map page = bookmarkService.listOffset([
            page      : params.page,
            size      : params.size,
            q         : params.q,
            visibility: params.visibility,
            tags      : params.list("tag")
        ], user?.username, lang(), acceptLanguage())
        json(page, 200, [
            "Deprecation": "@1782864000",
            "Sunset"    : "Thu, 01 Jul 2027 00:00:00 GMT",
            "Link"      : "</api/v2/bookmarks>; rel=\"successor-version\""
        ])
    }

    def listV2() {
        Map user = authService.currentUser()
        json(bookmarkService.listCursor([
            size      : params.size,
            q         : params.q,
            visibility: params.visibility,
            cursor    : params.cursor,
            tags      : params.list("tag")
        ], user?.username, lang(), acceptLanguage()))
    }

    def create() {
        Map user = authService.requireUser()
        Map created = bookmarkService.create(body(), user.username, lang(), acceptLanguage())
        json(created, 201, ["Location": "/api/v1/bookmarks/${created.id}"])
    }

    def show(String id) {
        Map user = authService.currentUser()
        json(bookmarkService.getVisible(uuid(id), user?.username))
    }

    def update(String id) {
        Map user = authService.requireUser()
        json(bookmarkService.update(uuid(id), body(), user.username, lang(), acceptLanguage()))
    }

    def delete(String id) {
        Map user = authService.requireUser()
        bookmarkService.delete(uuid(id), user.username)
        noContent()
    }

    def report(String id) {
        Map user = authService.requireUser()
        json(bookmarkService.report(uuid(id), body(), user.username, lang(), acceptLanguage()), 201)
    }
}
