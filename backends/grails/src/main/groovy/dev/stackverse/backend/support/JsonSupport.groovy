package dev.stackverse.backend.support

import groovy.json.JsonOutput
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.MediaType

class JsonSupport {
    static void writeJson(HttpServletResponse response, int status, Object body, Map<String, String> headers = [:]) {
        headers.each { key, value -> response.setHeader(key, value) }
        response.status = status
        response.contentType = MediaType.APPLICATION_JSON_VALUE
        response.characterEncoding = "UTF-8"
        response.writer.write(JsonOutput.toJson(stripNulls(body)))
    }

    static void writeProblem(HttpServletResponse response, ApiError error) {
        response.status = error.status
        response.contentType = MediaType.APPLICATION_PROBLEM_JSON_VALUE
        response.characterEncoding = "UTF-8"
        Map body = [
            type  : "about:blank",
            title : error.title,
            status: error.status,
            detail: error.message
        ]
        if (error.errors) {
            body.errors = error.errors
        }
        response.writer.write(JsonOutput.toJson(stripNulls(body)))
    }

    static String toJson(Object body) {
        JsonOutput.toJson(stripNulls(body))
    }

    private static Object stripNulls(Object value) {
        if (value instanceof Map) {
            LinkedHashMap copy = new LinkedHashMap()
            value.each { key, nested ->
                if (nested != null) {
                    copy[key] = stripNulls(nested)
                }
            }
            return copy
        }
        if (value instanceof Collection) {
            return value.collect { stripNulls(it) }
        }
        return value
    }
}
