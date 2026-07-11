package dev.stackverse.gateway

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress

/**
 * A recording stand-in for a frontend static server. It serves an SPA shell for
 * every request, matching the production frontend container's deep-link fallback,
 * and lets tests assert what the gateway forwarded.
 */
class StubFrontend : AutoCloseable {

    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)

    @Volatile var lastPath: String? = null

    @Volatile var lastCookie: String = ""

    init {
        server.createContext("/") { http ->
            lastPath = http.requestURI.path
            lastCookie = http.requestHeaders.getFirst("Cookie") ?: ""
            val body = "<!doctype html><title>Stackverse frontend stub</title>".toByteArray()
            http.responseHeaders.add("Content-Type", "text/html; charset=utf-8")
            http.sendResponseHeaders(200, body.size.toLong())
            http.responseBody.use { it.write(body) }
        }
        server.start()
    }

    val url: String get() = "http://127.0.0.1:${server.address.port}"

    override fun close() = server.stop(0)
}
