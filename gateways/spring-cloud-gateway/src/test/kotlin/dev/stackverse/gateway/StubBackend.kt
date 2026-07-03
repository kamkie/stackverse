package dev.stackverse.gateway

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * A recording stand-in for the backend API: answers every request with an empty
 * JSON document and remembers the headers of the last request, so tests can assert
 * what the token relay did (and did not) forward.
 */
class StubBackend : AutoCloseable {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    @Volatile var lastPath: String? = null
    @Volatile var lastAuthorization: String = ""
    @Volatile var lastCookie: String = ""
    @Volatile var lastCsrfHeader: String = ""

    init {
        server.createContext("/") { http ->
            lastPath = http.requestURI.path
            lastAuthorization = http.requestHeaders.getFirst("Authorization") ?: ""
            lastCookie = http.requestHeaders.getFirst("Cookie") ?: ""
            lastCsrfHeader = http.requestHeaders.getFirst("X-XSRF-TOKEN") ?: ""
            val body = """{"items":[]}""".toByteArray()
            http.responseHeaders.add("Content-Type", "application/json")
            http.sendResponseHeaders(200, body.size.toLong())
            http.responseBody.use { it.write(body) }
        }
        server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}
