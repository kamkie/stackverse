package dev.stackverse.backend.command

import dev.stackverse.backend.BookmarkController
import dev.stackverse.backend.message.MessageService
import dev.stackverse.backend.support.ApiError
import grails.testing.web.controllers.ControllerUnitTest
import spock.lang.Specification
import spock.lang.Unroll

class CommandBindingSpec extends Specification implements ControllerUnitTest<BookmarkController> {
    MessageService messages = Stub() {
        validationMessage(_, _, _) >> { String key, String language, String acceptLanguage -> key }
    }

    @Unroll
    void 'application/json binds into canonical #type command properties'() {
        when:
        request.json = payload
        controller.bindData(command, request.JSON)

        then:
        properties.every { String property, Object expected -> command."$property" == expected }

        where:
        type              | command                     | payload                                                                                                                     | properties
        'bookmark'        | new BookmarkCommand()       | [url: 'https://example.com', title: 'Example', notes: 'Notes', tags: ['grails', 'gorm'], visibility: 'public']             | [url: 'https://example.com', title: 'Example', notes: 'Notes', tags: ['grails', 'gorm'], visibility: 'public']
        'message'         | new MessageCommand()        | [key: 'example.message', language: 'en', text: 'Example', description: 'Description']                                      | [key: 'example.message', language: 'en', text: 'Example', description: 'Description']
        'report'          | new ReportCommand()         | [reason: 'spam', comment: 'Duplicate']                                                                                    | [reason: 'spam', comment: 'Duplicate']
        'resolution'      | new ResolutionCommand()     | [resolution: 'dismissed', note: 'Not actionable']                                                                         | [resolution: 'dismissed', note: 'Not actionable']
        'bookmark status' | new BookmarkStatusCommand() | [status: 'hidden', note: 'Policy violation']                                                                               | [status: 'hidden', note: 'Policy violation']
        'user status'     | new UserStatusCommand()     | [status: 'blocked', reason: 'Repeated abuse']                                                                              | [status: 'blocked', reason: 'Repeated abuse']
    }

    @Unroll
    void 'application/json rejects noncanonical #type property types'() {
        when:
        request.json = payload
        controller.bindData(command, request.JSON)
        command.validated(messages, null, null)

        then:
        ApiError error = thrown()
        error.errors*.field.contains(field)
        error.errors*.messageKey.every { it.startsWith('validation.') }
        error.errors.find { it.field == field }.messageKey == messageKey

        where:
        type                   | command                     | payload                                                              | field         | messageKey
        'bookmark tags'        | new BookmarkCommand()       | [url: 'https://example.com', title: 'Example', tags: 42]             | 'tags'        | 'validation.tag.invalid'
        'bookmark notes'       | new BookmarkCommand()       | [url: 'https://example.com', title: 'Example', notes: 42]            | 'notes'       | 'validation.notes.too-long'
        'message description'  | new MessageCommand()        | [key: 'example', language: 'en', text: 'x', description: 42]         | 'description' | 'validation.message.description.too-long'
        'report comment'       | new ReportCommand()         | [reason: 'spam', comment: 42]                                        | 'comment'     | 'validation.report.comment.too-long'
        'resolution note'      | new ResolutionCommand()     | [resolution: 'dismissed', note: 42]                                  | 'note'        | 'validation.resolution.note.too-long'
        'bookmark status note' | new BookmarkStatusCommand() | [status: 'active', note: 42]                                         | 'note'        | 'validation.bookmark-status.note.too-long'
        'user status reason'   | new UserStatusCommand()     | [status: 'active', reason: 42]                                       | 'reason'      | 'validation.block.reason.too-long'
    }
}
