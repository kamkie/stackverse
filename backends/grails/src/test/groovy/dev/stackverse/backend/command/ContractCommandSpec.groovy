package dev.stackverse.backend.command

import dev.stackverse.backend.message.MessageService
import spock.lang.Specification

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

    def "bookmark command counts normalized tags for the contract limit"() {
        given:
        BookmarkCommand command = new BookmarkCommand(
            url: 'https://example.com',
            title: 'Example',
            tags: ['one', 'one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten']
        )

        expect:
        command.validated(messages, null, null).tags ==
            ['one', 'two', 'three', 'four', 'five', 'six', 'seven', 'eight', 'nine', 'ten']
    }
}
