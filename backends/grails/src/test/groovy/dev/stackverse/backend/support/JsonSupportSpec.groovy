package dev.stackverse.backend.support

import groovy.json.JsonSlurper
import org.springframework.mock.web.MockHttpServletResponse
import spock.lang.Specification

class JsonSupportSpec extends Specification {
    void 'JSON responses set contract headers and omit nested null properties'() {
        given:
        MockHttpServletResponse response = new MockHttpServletResponse()

        when:
        JsonSupport.writeJson(response, 201, [
            id      : 'bookmark-1',
            optional: null,
            nested  : [kept: 'value', omitted: null],
            items   : [[name: 'first', note: null]]
        ], ['Location': '/api/v1/bookmarks/bookmark-1'])

        then:
        response.status == 201
        response.contentType.startsWith('application/json')
        response.characterEncoding == 'UTF-8'
        response.getHeader('Location') == '/api/v1/bookmarks/bookmark-1'
        json(response) == [
            id    : 'bookmark-1',
            nested: [kept: 'value'],
            items : [[name: 'first']]
        ]
    }

    void 'problem responses preserve localized field errors without optional nulls'() {
        given:
        MockHttpServletResponse response = new MockHttpServletResponse()
        ApiError error = ApiError.badRequest('Validation failed.', [[
            field     : 'url',
            messageKey: 'validation.url.invalid',
            message   : 'URL must be an absolute HTTP or HTTPS URL.',
            optional  : null
        ]])

        when:
        JsonSupport.writeProblem(response, error)

        then:
        response.status == 400
        response.contentType.startsWith('application/problem+json')
        response.characterEncoding == 'UTF-8'
        json(response) == [
            type  : 'about:blank',
            title : 'Bad Request',
            status: 400,
            detail: 'Validation failed.',
            errors: [[
                field     : 'url',
                messageKey: 'validation.url.invalid',
                message   : 'URL must be an absolute HTTP or HTTPS URL.'
            ]]
        ]
    }

    private static Map json(MockHttpServletResponse response) {
        new JsonSlurper().parseText(response.contentAsString) as Map
    }
}
