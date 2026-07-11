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
import java.time.LocalDate
import java.time.ZoneOffset

@Integration
@Import(TestJwtConfiguration)
@Requires({ env.STACKVERSE_DB_TESTS == 'true' })
class ContractWebBoundarySpec extends Specification {
    private final HttpClient client = HttpClient.newHttpClient()

    @Value('${local.server.port}')
    int serverPort

    void 'authentication and role failures are stateless RFC 9457 responses'() {
        when:
        HttpResponse<String> anonymous = request('GET', '/api/v1/me')
        HttpResponse<String> forbidden = request('GET', '/api/v1/admin/stats', 'regular-user', 'regular')
        HttpResponse<String> invalid = requestWithToken('GET', '/api/v1/me', 'invalid')

        then:
        anonymous.statusCode() == 401
        problem(anonymous).title == 'Unauthorized'
        forbidden.statusCode() == 403
        problem(forbidden).title == 'Forbidden'
        invalid.statusCode() == 401
        problem(invalid).detail == 'Missing or invalid bearer token.'
        [anonymous, forbidden, invalid].every { !it.headers().firstValue('Set-Cookie').isPresent() }
    }

    void 'blocking takes effect on the next request while the anonymous public surface remains available'() {
        given:
        String suffix = UUID.randomUUID().toString()
        String target = "blocked-${suffix}"
        String admin = "admin-${suffix}"

        when:
        HttpResponse<String> provisioned = request('GET', '/api/v1/me', target, 'regular')
        HttpResponse<String> blocked = request('PUT', "/api/v1/admin/users/${target}/status", admin, 'admin', [
            status: 'blocked', reason: 'contract boundary'
        ])
        HttpResponse<String> rejected = request('GET', '/api/v1/me', target, 'regular')
        HttpResponse<String> publicBundle = request('GET', '/api/v1/messages/bundle')

        then:
        provisioned.statusCode() == 200
        blocked.statusCode() == 200
        json(blocked.body()).status == 'blocked'
        rejected.statusCode() == 403
        problem(rejected).detail == 'Your account has been blocked.'
        !rejected.headers().firstValue('Set-Cookie').isPresent()
        publicBundle.statusCode() == 200
    }

    void 'message bundles and admin stats revalidate with stable ETags and empty 304 bodies'() {
        given:
        String moderator = "moderator-${UUID.randomUUID()}"

        when:
        HttpResponse<String> bundle = request('GET', '/api/v1/messages/bundle', null, 'regular', null, [
            'Accept-Language': 'pl-PL, en;q=0.5'
        ])
        String bundleEtag = bundle.headers().firstValue('ETag').orElse(null)
        HttpResponse<String> bundleCached = request('GET', '/api/v1/messages/bundle', null, 'regular', null, [
            'Accept-Language': 'pl-PL, en;q=0.5',
            'If-None-Match' : bundleEtag
        ])
        Map statsPair = stableStatsRevalidation(moderator)
        HttpResponse<String> stats = statsPair.initial as HttpResponse<String>
        String statsEtag = statsPair.etag as String
        HttpResponse<String> statsCached = statsPair.cached as HttpResponse<String>

        then:
        bundle.statusCode() == 200
        bundleEtag
        bundle.headers().firstValue('Cache-Control').orElse(null) == 'no-cache'
        bundle.headers().firstValue('Content-Language').orElse(null) == 'pl'
        bundleCached.statusCode() == 304
        bundleCached.body().isEmpty()
        stats.statusCode() == 200
        statsEtag
        json(stats.body()).daily.size() == 30
        statsCached.statusCode() == 304
        statsCached.body().isEmpty()
    }

    void 'malformed resource ids, missing routes, liveness, and readiness cross the real Grails HTTP boundary'() {
        when:
        HttpResponse<String> malformed = request('GET', '/api/v1/bookmarks/not-a-uuid')
        HttpResponse<String> missing = request('GET', '/api/v1/does-not-exist')
        HttpResponse<String> live = request('GET', '/healthz')
        HttpResponse<String> ready = request('GET', '/readyz')

        then:
        malformed.statusCode() == 404
        problem(malformed).detail == 'Resource not found.'
        missing.statusCode() == 404
        problem(missing).title == 'Not Found'
        live.statusCode() == 200
        live.body() == 'ok'
        ready.statusCode() == 200
        ready.body() == 'ok'
    }

    private HttpResponse<String> request(String method, String path, String username = null, String role = 'regular',
                                         Map<String, Object> body = null, Map<String, String> headers = [:]) {
        String token = username == null ? null : "${username}.${role}"
        requestWithToken(method, path, token, body, headers)
    }

    private HttpResponse<String> requestWithToken(String method, String path, String token,
                                                   Map<String, Object> body = null, Map<String, String> headers = [:]) {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create("http://localhost:${serverPort}${path}"))
            .header('Accept', 'application/json')
        if (token) {
            builder.header('Authorization', "Bearer ${token}")
        }
        headers.each { key, value -> builder.header(key, value) }
        if (body == null) {
            builder.method(method, HttpRequest.BodyPublishers.noBody())
        } else {
            builder.header('Content-Type', 'application/json')
                .method(method, HttpRequest.BodyPublishers.ofString(JsonOutput.toJson(body)))
        }
        client.send(builder.build(), HttpResponse.BodyHandlers.ofString())
    }

    private Map stableStatsRevalidation(String moderator) {
        for (int attempt = 0; attempt < 3; attempt++) {
            LocalDate before = LocalDate.now(ZoneOffset.UTC)
            HttpResponse<String> initial = request('GET', '/api/v1/admin/stats', moderator, 'moderator')
            String etag = initial.headers().firstValue('ETag').orElse(null)
            HttpResponse<String> cached = request('GET', '/api/v1/admin/stats', moderator, 'moderator', null, [
                'If-None-Match': etag
            ])
            if (before == LocalDate.now(ZoneOffset.UTC)) {
                return [initial: initial, etag: etag, cached: cached]
            }
        }
        throw new IllegalStateException('Unable to test stats revalidation across a stable UTC date.')
    }

    private static Map json(String body) {
        new JsonSlurper().parseText(body) as Map
    }

    private static Map problem(HttpResponse<String> response) {
        assert response.headers().firstValue('Content-Type').orElse('').startsWith('application/problem+json')
        json(response.body())
    }
}
