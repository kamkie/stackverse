package dev.stackverse.backend.command

import grails.testing.mixin.integration.Integration
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Value
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.jwt.JwtDecoder
import spock.lang.Requires
import spock.lang.Specification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Instant

@Integration
@Import(TestJwtConfiguration)
@Requires({ env.STACKVERSE_DB_TESTS == 'true' })
class CommandWebBindingSpec extends Specification {
    private final HttpClient client = HttpClient.newHttpClient()

    @Value('${local.server.port}')
    int serverPort

    void 'malformed application/json returns the exact contract problem'() {
        given:
        String owner = "web-owner-${UUID.randomUUID()}"

        when:
        HttpResponse<String> response = jsonRequest('POST', '/api/v1/bookmarks', owner, 'regular', [
            url: 'https://example.com', title: 'Example', notes: 42
        ])

        then:
        response.statusCode() == 400
        response.headers().firstValue('Content-Type').orElse('').startsWith('application/problem+json')
        json(response.body()) == [
            type  : 'about:blank',
            title : 'Bad Request',
            status: 400,
            detail: 'Validation failed.',
            errors: [[
                field     : 'notes',
                messageKey: 'validation.notes.too-long',
                message   : 'Notes must be at most 4000 characters.'
            ]]
        ]
    }

    void 'bookmark application/json binds through the real HTTP action boundary'() {
        given:
        String owner = "web-owner-${UUID.randomUUID()}"

        when:
        HttpResponse<String> response = jsonRequest('POST', '/api/v1/bookmarks', owner, 'regular', [
            url: 'https://example.com/web-binding', title: 'Web-bound bookmark',
            notes: 'Bound notes', tags: [' Grails ', 'gorm'], visibility: 'public'
        ])
        Map bookmark = json(response.body())

        then:
        response.statusCode() == 201
        bookmark.owner == owner
        bookmark.title == 'Web-bound bookmark'
        bookmark.notes == 'Bound notes'
        bookmark.tags as Set == ['grails', 'gorm'] as Set
        bookmark.visibility == 'public'
    }

    void 'message application/json binds through the real HTTP action boundary'() {
        given:
        String suffix = UUID.randomUUID().toString()
        String admin = "web-admin-${suffix}"

        when:
        HttpResponse<String> response = jsonRequest('POST', '/api/v1/messages', admin, 'admin', [
            key: "web-binding.${suffix}", language: 'en', text: 'Web-bound message',
            description: 'Bound description'
        ])
        Map message = json(response.body())

        then:
        response.statusCode() == 201
        message.key == "web-binding.${suffix}"
        message.language == 'en'
        message.text == 'Web-bound message'
        message.description == 'Bound description'

        when:
        HttpResponse<String> duplicateResponse = jsonRequest('POST', '/api/v1/messages', admin, 'admin', [
            key: message.key, language: message.language, text: 'Duplicate message'
        ])

        then:
        duplicateResponse.statusCode() == 409
    }

    void 'user-status application/json binds through the real HTTP action boundary'() {
        given:
        String suffix = UUID.randomUUID().toString()
        String target = "web-target-${suffix}"
        String admin = "web-admin-${suffix}"

        when:
        HttpResponse<String> targetResponse = jsonRequest('GET', '/api/v1/me', target, 'regular')
        HttpResponse<String> response = jsonRequest('PUT', "/api/v1/admin/users/${target}/status", admin, 'admin', [
            status: 'blocked', reason: 'Bound block reason'
        ])
        Map account = json(response.body())

        then:
        targetResponse.statusCode() == 200
        response.statusCode() == 200
        account.username == target
        account.status == 'blocked'
        account.blockedReason == 'Bound block reason'
    }

    void 'report and moderation application/json bind and return fresh persisted state'() {
        given:
        String suffix = UUID.randomUUID().toString()
        String owner = "web-owner-${suffix}"
        String reporter = "web-reporter-${suffix}"
        String admin = "web-admin-${suffix}"
        Map bookmark = json(jsonRequest('POST', '/api/v1/bookmarks', owner, 'regular', [
            url: 'https://example.com/moderation', title: 'Moderation bookmark', visibility: 'public'
        ]).body())

        when:
        HttpResponse<String> reportResponse = jsonRequest('POST', "/api/v1/bookmarks/${bookmark.id}/reports",
            reporter, 'regular', [reason: 'spam', comment: 'Bound report comment'])
        Map report = json(reportResponse.body())

        then:
        reportResponse.statusCode() == 201
        report.bookmarkId == bookmark.id
        report.reporter == reporter
        report.reason == 'spam'
        report.comment == 'Bound report comment'

        when:
        HttpResponse<String> duplicateReportResponse = jsonRequest(
            'POST',
            "/api/v1/bookmarks/${bookmark.id}/reports",
            reporter,
            'regular',
            [reason: 'other', comment: 'Duplicate report']
        )

        then:
        duplicateReportResponse.statusCode() == 409

        when:
        HttpResponse<String> resolutionResponse = jsonRequest('PUT', "/api/v1/admin/reports/${report.id}",
            admin, 'admin', [resolution: 'dismissed', note: 'Bound resolution note'])
        Map resolved = json(resolutionResponse.body())

        then:
        resolutionResponse.statusCode() == 200
        resolved.status == 'dismissed'
        resolved.resolutionNote == 'Bound resolution note'

        when:
        HttpResponse<String> statusResponse = jsonRequest('PUT', "/api/v1/admin/bookmarks/${bookmark.id}/status",
            admin, 'admin', [status: 'hidden', note: 'Bound moderation note'])
        Map hidden = json(statusResponse.body())

        then:
        statusResponse.statusCode() == 200
        hidden.status == 'hidden'
    }

    private HttpResponse<String> jsonRequest(String method, String path, String username, String role,
                                             Map<String, Object> body = null) {
        HttpRequest.Builder request = HttpRequest.newBuilder(URI.create("http://localhost:${serverPort}${path}"))
            .header('Authorization', "Bearer ${username}.${role}")
            .header('Accept', 'application/json')
        if (body == null) {
            request.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            request.header('Content-Type', 'application/json')
                .method(method, HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
        }
        client.send(request.build(), HttpResponse.BodyHandlers.ofString())
    }

    private static Map json(String body) {
        new JsonSlurper().parseText(body) as Map
    }
}

@TestConfiguration(proxyBeanMethods = false)
class TestJwtConfiguration {
    @Bean
    @Primary
    JwtDecoder testJwtDecoder() {
        { String token ->
            List<String> parts = token.tokenize('.')
            String username = parts[0]
            List<String> roles = switch (parts.size() > 1 ? parts[1] : 'regular') {
                case 'admin' -> ['moderator', 'admin']
                case 'moderator' -> ['moderator']
                default -> []
            }
            Instant now = Instant.now()
            Jwt.withTokenValue(token)
                .header('alg', 'none')
                .subject("subject-of-${username}")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(300))
                .claim('preferred_username', username)
                .claim('realm_access', [roles: roles + 'default-roles-stackverse'])
                .claim('name', "${username} User")
                .claim('email', "${username}@stackverse.local")
                .build()
        } as JwtDecoder
    }
}
