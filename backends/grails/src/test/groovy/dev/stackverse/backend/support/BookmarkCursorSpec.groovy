package dev.stackverse.backend.support

import spock.lang.Specification

import java.time.Instant

class BookmarkCursorSpec extends Specification {
    def "cursor round-trips createdAt and id"() {
        given:
        def cursor = new BookmarkCursor(Instant.parse("2026-07-05T10:15:30.123456Z"), UUID.fromString("11111111-1111-1111-1111-111111111111"))

        when:
        def decoded = BookmarkCursor.decode(cursor.encode())

        then:
        decoded.createdAt == cursor.createdAt
        decoded.id == cursor.id
    }

    def "malformed cursor is a 400 problem"() {
        when:
        BookmarkCursor.decode("not-base64")

        then:
        def error = thrown(ApiError)
        error.status == 400
    }
}
