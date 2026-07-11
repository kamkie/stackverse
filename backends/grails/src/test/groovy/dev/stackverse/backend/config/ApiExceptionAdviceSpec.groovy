package dev.stackverse.backend.config

import dev.stackverse.backend.support.ApiError
import groovy.json.JsonSlurper
import org.springframework.dao.DuplicateKeyException
import org.springframework.mock.web.MockHttpServletResponse
import org.springframework.web.servlet.NoHandlerFoundException
import spock.lang.Specification

class ApiExceptionAdviceSpec extends Specification {
    ApiExceptionAdvice advice = new ApiExceptionAdvice()

    void 'typed application errors keep their exact RFC 9457 status and detail'() {
        given:
        MockHttpServletResponse response = new MockHttpServletResponse()

        when:
        advice.api(ApiError.forbidden('The account is blocked.'), response)

        then:
        response.status == 403
        problem(response) == [
            type  : 'about:blank',
            title : 'Forbidden',
            status: 403,
            detail: 'The account is blocked.'
        ]
    }

    void 'database uniqueness failures map to the generic conflict problem'() {
        given:
        MockHttpServletResponse response = new MockHttpServletResponse()

        when:
        advice.duplicate(new DuplicateKeyException('constraint and bound values'), response)

        then:
        response.status == 409
        problem(response).detail == 'The requested operation conflicts with the current resource state.'
        !response.contentAsString.contains('constraint')
        !response.contentAsString.contains('bound values')
    }

    void 'missing routes map to the contract not-found problem'() {
        given:
        MockHttpServletResponse response = new MockHttpServletResponse()

        when:
        advice.notFound(new NoHandlerFoundException('GET', '/missing', null), response)

        then:
        response.status == 404
        problem(response).title == 'Not Found'
    }

    void 'unexpected failures return a sanitized server problem'() {
        given:
        MockHttpServletResponse response = new MockHttpServletResponse()

        when:
        advice.unexpected(new IllegalStateException('password=do-not-leak'), response)

        then:
        response.status == 500
        problem(response) == [
            type  : 'about:blank',
            title : 'Internal Server Error',
            status: 500,
            detail: 'An unexpected server error occurred.'
        ]
        !response.contentAsString.contains('password')
        !response.contentAsString.contains('do-not-leak')
    }

    private static Map problem(MockHttpServletResponse response) {
        assert response.contentType.startsWith('application/problem+json')
        new JsonSlurper().parseText(response.contentAsString) as Map
    }
}
