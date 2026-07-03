package dev.stackverse.gateway.common

import org.springframework.core.io.buffer.DataBufferUtils
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.server.ServerWebExchange
import reactor.core.publisher.Mono

/** Minimal RFC 9457 problem documents for responses the gateway itself produces. */
object Problems {

    fun write(exchange: ServerWebExchange, status: HttpStatus, title: String, detail: String): Mono<Void> {
        val response = exchange.response
        response.statusCode = status
        response.headers.contentType = MediaType.APPLICATION_PROBLEM_JSON
        val body = """{"type":"about:blank","title":${json(title)},"status":${status.value()},"detail":${json(detail)}}"""
        val buffer = response.bufferFactory().wrap(body.toByteArray(Charsets.UTF_8))
        return response.writeWith(Mono.just(buffer)).doOnError { DataBufferUtils.release(buffer) }
    }

    /** The two fields are gateway-authored constants, but encode defensively anyway. */
    private fun json(value: String): String =
        "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}
