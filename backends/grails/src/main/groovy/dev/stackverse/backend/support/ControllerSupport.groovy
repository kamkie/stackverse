package dev.stackverse.backend.support

import java.security.MessageDigest

trait ControllerSupport {
    Map body() {
        def json = request.JSON
        json instanceof Map ? (json as Map) : [:]
    }

    UUID uuid(String value) {
        try {
            UUID.fromString(value)
        } catch (Exception ignored) {
            throw ApiError.notFound()
        }
    }

    String acceptLanguage() {
        request.getHeader("Accept-Language")
    }

    String lang() {
        params.lang
    }

    void json(Object body, int status = 200, Map<String, String> headers = [:]) {
        headers.each { key, value -> response.setHeader(key, value) }
        render(text: JsonSupport.toJson(body), status: status, contentType: "application/json", encoding: "UTF-8")
    }

    void noContent() {
        render(text: "", status: 204)
    }

    void problem(ApiError error) {
        Map body = [
            type  : "about:blank",
            title : error.title,
            status: error.status,
            detail: error.message
        ]
        if (error.errors) {
            body.errors = error.errors
        }
        render(text: JsonSupport.toJson(body), status: error.status, contentType: "application/problem+json", encoding: "UTF-8")
    }

    void cached(Object body, Map<String, String> headers = [:]) {
        String text = JsonSupport.toJson(body)
        String etag = "\"${MessageDigest.getInstance("SHA-256").digest(text.bytes).encodeHex()}\""
        response.setHeader("ETag", etag)
        response.setHeader("Cache-Control", "no-cache")
        headers.each { key, value -> response.setHeader(key, value) }
        if (request.getHeader("If-None-Match") == etag) {
            render(text: "", status: 304)
            return
        }
        render(text: text, status: 200, contentType: "application/json", encoding: "UTF-8")
    }
}
