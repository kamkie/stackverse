package dev.stackverse.backend.command

import grails.gorm.transactions.Rollback
import grails.testing.mixin.integration.Integration
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.http.MediaType
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.request.RequestPostProcessor
import spock.lang.Requires
import spock.lang.Specification

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put

@Integration
@Rollback
@AutoConfigureMockMvc
@Requires({ env.STACKVERSE_DB_TESTS == 'true' })
class CommandWebBindingSpec extends Specification {
    @Autowired
    MockMvc mockMvc

    void 'application/json command payloads bind through the real HTTP action boundary'() {
        given:
        String suffix = UUID.randomUUID().toString()
        String owner = "web-owner-${suffix}"
        String reporter = "web-reporter-${suffix}"
        String target = "web-target-${suffix}"
        String admin = "web-admin-${suffix}"

        when: 'strict JSON types fail as an exact contract problem document'
        def invalidResponse = mockMvc.perform(
            post('/api/v1/bookmarks')
                .with(jwtUser(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonOutput.toJson([url: 'https://example.com', title: 'Example', notes: 42]))
        ).andReturn().response

        then:
        invalidResponse.status == 400
        invalidResponse.contentType.startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE)
        json(invalidResponse.contentAsString) == [
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

        when: 'bookmark JSON reaches BookmarkCommand and its controller action'
        def bookmarkResponse = mockMvc.perform(
            post('/api/v1/bookmarks')
                .with(jwtUser(owner))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonOutput.toJson([
                    url: 'https://example.com/web-binding', title: 'Web-bound bookmark',
                    notes: 'Bound notes', tags: [' Grails ', 'gorm'], visibility: 'public'
                ]))
        ).andReturn().response
        Map bookmark = json(bookmarkResponse.contentAsString)

        then:
        bookmarkResponse.status == 201
        bookmark.owner == owner
        bookmark.title == 'Web-bound bookmark'
        bookmark.notes == 'Bound notes'
        bookmark.tags as Set == ['grails', 'gorm'] as Set
        bookmark.visibility == 'public'

        when: 'message JSON reaches MessageCommand and its controller action'
        def messageResponse = mockMvc.perform(
            post('/api/v1/messages')
                .with(jwtUser(admin, 'moderator', 'admin'))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonOutput.toJson([
                    key: "web-binding.${suffix}", language: 'en', text: 'Web-bound message',
                    description: 'Bound description'
                ]))
        ).andReturn().response
        Map message = json(messageResponse.contentAsString)

        then:
        messageResponse.status == 201
        message.key == "web-binding.${suffix}"
        message.language == 'en'
        message.text == 'Web-bound message'
        message.description == 'Bound description'

        when: 'user-status JSON reaches UserStatusCommand and its controller action'
        def targetResponse = mockMvc.perform(get('/api/v1/me').with(jwtUser(target))).andReturn().response
        def statusResponse = mockMvc.perform(
            put("/api/v1/admin/users/${target}/status")
                .with(jwtUser(admin, 'moderator', 'admin'))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonOutput.toJson([status: 'blocked', reason: 'Bound block reason']))
        ).andReturn().response
        Map account = json(statusResponse.contentAsString)

        then:
        targetResponse.status == 200
        statusResponse.status == 200
        account.username == target
        account.status == 'blocked'
        account.blockedReason == 'Bound block reason'

        when: 'report JSON reaches ReportCommand and its controller action'
        def reportResponse = mockMvc.perform(
            post("/api/v1/bookmarks/${bookmark.id}/reports")
                .with(jwtUser(reporter))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonOutput.toJson([reason: 'spam', comment: 'Bound report comment']))
        ).andReturn().response
        Map report = json(reportResponse.contentAsString)

        then:
        reportResponse.status == 201
        report.bookmarkId == bookmark.id
        report.reporter == reporter
        report.reason == 'spam'
        report.comment == 'Bound report comment'

        when: 'resolution JSON reaches ResolutionCommand and returns the fresh persisted state'
        def resolutionResponse = mockMvc.perform(
            put("/api/v1/admin/reports/${report.id}")
                .with(jwtUser(admin, 'moderator', 'admin'))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonOutput.toJson([resolution: 'dismissed', note: 'Bound resolution note']))
        ).andReturn().response
        Map resolved = json(resolutionResponse.contentAsString)

        then:
        resolutionResponse.status == 200
        resolved.status == 'dismissed'
        resolved.resolutionNote == 'Bound resolution note'

        when: 'bookmark-status JSON reaches BookmarkStatusCommand and its controller action'
        def bookmarkStatusResponse = mockMvc.perform(
            put("/api/v1/admin/bookmarks/${bookmark.id}/status")
                .with(jwtUser(admin, 'moderator', 'admin'))
                .contentType(MediaType.APPLICATION_JSON)
                .content(JsonOutput.toJson([status: 'hidden', note: 'Bound moderation note']))
        ).andReturn().response
        Map hidden = json(bookmarkStatusResponse.contentAsString)

        then:
        bookmarkStatusResponse.status == 200
        hidden.status == 'hidden'
    }

    private static RequestPostProcessor jwtUser(String username, String... roles) {
        List<String> roleList = roles as List<String>
        Jwt jwt = Jwt.withTokenValue('test-token')
            .header('alg', 'none')
            .subject("subject-of-${username}")
            .claim('preferred_username', username)
            .claim('realm_access', [roles: roleList + 'default-roles-stackverse'])
            .claim('name', "${username} User")
            .claim('email', "${username}@stackverse.local")
            .build()
        def authorities = (roleList + 'default-roles-stackverse').collect {
            new SimpleGrantedAuthority("ROLE_${it}")
        }
        authentication(new JwtAuthenticationToken(jwt, authorities, username))
    }

    private static Map json(String body) {
        new JsonSlurper().parseText(body) as Map
    }
}
