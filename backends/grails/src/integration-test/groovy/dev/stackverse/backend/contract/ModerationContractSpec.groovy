package dev.stackverse.backend.contract

import dev.stackverse.backend.command.TestJwtConfiguration
import grails.testing.mixin.integration.Integration
import groovy.json.JsonOutput
import groovy.json.JsonSlurper
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Import
import spock.lang.Requires
import spock.lang.Specification

import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse

@Integration
@Import(TestJwtConfiguration)
@Requires({ env.STACKVERSE_DB_TESTS == 'true' })
class ModerationContractSpec extends Specification {
    private final HttpClient client = HttpClient.newHttpClient()

    @Value('${local.server.port}')
    int serverPort

    void 'reporter and moderator state transitions persist through the complete HTTP workflow'() {
        given:
        String suffix = UUID.randomUUID().toString()
        String owner = "owner-${suffix}"
        String firstReporter = "first-${suffix}"
        String secondReporter = "second-${suffix}"
        String moderator = "moderator-${suffix}"
        String admin = "admin-${suffix}"
        Map bookmark = json(request('POST', '/api/v1/bookmarks', owner, 'regular', [
            url: "https://example.com/${suffix}", title: 'Moderation workflow', visibility: 'public'
        ]).body())
        Map first = json(request('POST', "/api/v1/bookmarks/${bookmark.id}/reports", firstReporter, 'regular', [
            reason: 'spam', comment: 'initial comment'
        ]).body())
        Map second = json(request('POST', "/api/v1/bookmarks/${bookmark.id}/reports", secondReporter, 'regular', [
            reason: 'offensive', comment: 'second comment'
        ]).body())

        when:
        HttpResponse<String> updateResponse = request('PUT', "/api/v1/reports/${first.id}", firstReporter, 'regular', [
            reason: 'broken-link', comment: 'revised comment'
        ])
        Map mine = json(request('GET', '/api/v1/reports?status=open', firstReporter, 'regular').body())
        HttpResponse<String> actionResponse = request('PUT', "/api/v1/admin/reports/${first.id}", moderator, 'moderator', [
            resolution: 'actioned', note: 'policy decision'
        ])
        Map secondAfterAction = json(request('GET', '/api/v1/reports?status=actioned', secondReporter, 'regular').body())
        HttpResponse<String> anonymousHidden = request('GET', "/api/v1/bookmarks/${bookmark.id}")
        Map ownerHidden = json(request('GET', "/api/v1/bookmarks/${bookmark.id}", owner, 'regular').body())

        then:
        updateResponse.statusCode() == 200
        json(updateResponse.body()).reason == 'broken-link'
        mine.items*.id == [first.id]
        actionResponse.statusCode() == 200
        json(actionResponse.body()).status == 'actioned'
        secondAfterAction.items*.id.contains(second.id)
        secondAfterAction.items.find { it.id == second.id }.resolutionNote == 'policy decision'
        anonymousHidden.statusCode() == 404
        ownerHidden.status == 'hidden'

        when:
        HttpResponse<String> reopenResponse = request('PUT', "/api/v1/admin/reports/${first.id}", moderator, 'moderator', [
            resolution: 'open', note: 'must be ignored'
        ])
        HttpResponse<String> restoreResponse = request('PUT', "/api/v1/admin/bookmarks/${bookmark.id}/status",
            moderator, 'moderator', [status: 'active', note: 'manual restore'])
        HttpResponse<String> publicAgain = request('GET', "/api/v1/bookmarks/${bookmark.id}")
        HttpResponse<String> withdrawResponse = request('DELETE', "/api/v1/reports/${first.id}", firstReporter, 'regular')
        Map audit = json(request('GET', "/api/v1/admin/audit-log?action=report.resolved&targetId=${first.id}",
            admin, 'admin').body())

        then:
        reopenResponse.statusCode() == 200
        Map reopened = json(reopenResponse.body())
        reopened.status == 'open'
        !reopened.containsKey('resolvedBy')
        !reopened.containsKey('resolvedAt')
        !reopened.containsKey('resolutionNote')
        restoreResponse.statusCode() == 200
        json(restoreResponse.body()).status == 'active'
        publicAgain.statusCode() == 200
        withdrawResponse.statusCode() == 204
        audit.items*.action == ['report.resolved']
        audit.items*.targetId == [first.id]
    }

    void 'reopening an older report conflicts when the reporter already has a newer open report'() {
        given:
        String suffix = UUID.randomUUID().toString()
        String owner = "owner-${suffix}"
        String reporter = "reporter-${suffix}"
        String moderator = "moderator-${suffix}"
        Map bookmark = json(request('POST', '/api/v1/bookmarks', owner, 'regular', [
            url: "https://example.com/reopen/${suffix}", title: 'Reopen conflict', visibility: 'public'
        ]).body())
        Map older = json(request('POST', "/api/v1/bookmarks/${bookmark.id}/reports", reporter, 'regular', [
            reason: 'spam'
        ]).body())
        assert request('PUT', "/api/v1/admin/reports/${older.id}", moderator, 'moderator', [
            resolution: 'dismissed', note: 'first decision'
        ]).statusCode() == 200
        Map newer = json(request('POST', "/api/v1/bookmarks/${bookmark.id}/reports", reporter, 'regular', [
            reason: 'other'
        ]).body())

        when:
        HttpResponse<String> response = request('PUT', "/api/v1/admin/reports/${older.id}", moderator, 'moderator', [
            resolution: 'open'
        ])

        then:
        newer.status == 'open'
        response.statusCode() == 409
        problem(response).detail == 'The reporter already has another open report on this bookmark.'
    }

    private HttpResponse<String> request(String method, String path, String username = null, String role = 'regular',
                                         Map<String, Object> body = null) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:${serverPort}${path}"))
            .header('Accept', 'application/json')
        if (username) {
            builder.header('Authorization', "Bearer ${username}.${role}")
        }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header('Content-Type', 'application/json')
                .method(method, HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
        }
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private static Map json(String body) {
        new JsonSlurper().parseText(body) as Map
    }

    private static Map problem(HttpResponse<String> response) {
        assert response.headers().firstValue('Content-Type').orElse('').startsWith('application/problem+json')
        json(response.body())
    }
}
