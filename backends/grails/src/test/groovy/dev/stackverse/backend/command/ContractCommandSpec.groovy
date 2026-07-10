package dev.stackverse.backend.command

import dev.stackverse.backend.message.MessageService
import dev.stackverse.backend.support.ApiError
import spock.lang.Specification
import spock.lang.Unroll

class ContractCommandSpec extends Specification {
    MessageService messages = Stub() {
        validationMessage(_, _, _) >> { String key, String language, String acceptLanguage -> key }
    }

    def "bookmark command validates and normalizes through Grails constraints"() {
        given:
        BookmarkCommand command = new BookmarkCommand(
            url: ' https://example.com ',
            title: 'Example',
            tags: [' Grails ', 'grails', 'gorm'],
            visibility: null
        )

        expect:
        command.validated(messages, null, null) == [
            url       : 'https://example.com',
            title     : 'Example',
            notes     : null,
            tags      : ['grails', 'gorm'],
            visibility: 'private'
        ]
    }

    @Unroll
    def "bookmark command rejects malformed typed value for #field"() {
        given:
        BookmarkCommand command = new BookmarkCommand(url: 'https://example.com', title: 'Example')
        command."$field" = value

        when:
        command.validated(messages, null, null)

        then:
        ApiError error = thrown()
        error.errors*.field.contains(field)
        error.errors*.messageKey.contains(messageKey)

        where:
        field   | value || messageKey
        'tags'  | 42    || 'validation.tag.invalid'
        'tags'  | [42]  || 'validation.tag.invalid'
        'notes' | 42    || 'validation.notes.too-long'
    }

    @Unroll
    def "#type command rejects malformed optional strings"() {
        when:
        command.validated(messages, null, null)

        then:
        ApiError error = thrown()
        error.errors*.messageKey.contains(messageKey)

        where:
        type         | command                                                                                  || messageKey
        'message'    | new MessageCommand(key: 'example', language: 'en', text: 'x', description: 42)           || 'validation.message.description.too-long'
        'report'     | new ReportCommand(reason: 'spam', comment: 42)                                           || 'validation.report.comment.too-long'
        'resolution' | new ResolutionCommand(resolution: 'dismissed', note: 42)                                 || 'validation.resolution.note.too-long'
        'status'     | new BookmarkStatusCommand(status: 'active', note: 42)                                    || 'validation.bookmark-status.note.too-long'
        'user'       | new UserStatusCommand(status: 'active', reason: 42)                                      || 'validation.block.reason.too-long'
    }
}
