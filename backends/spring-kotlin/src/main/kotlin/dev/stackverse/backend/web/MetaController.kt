package dev.stackverse.backend.web

import org.springframework.dao.DataAccessException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/** Liveness/readiness for the container runtime; not proxied by the gateway. */
@RestController
class MetaController(private val jdbcClient: JdbcClient) {

    @GetMapping("/healthz")
    fun healthz(): Map<String, String> = mapOf("status" to "up")

    @GetMapping("/readyz")
    fun readyz(): ResponseEntity<Map<String, String>> = try {
        jdbcClient.sql("select 1").query(Int::class.java).single()
        ResponseEntity.ok(mapOf("status" to "ready"))
    } catch (e: DataAccessException) {
        ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(mapOf("status" to "unavailable"))
    }
}
