package dev.stackverse.backend

import dev.stackverse.backend.support.ControllerSupport
import org.springframework.jdbc.core.JdbcTemplate

class MetaController implements ControllerSupport {
    JdbcTemplate jdbcTemplate

    def healthz() {
        render(text: "ok", status: 200)
    }

    def readyz() {
        try {
            jdbcTemplate.queryForObject("select 1", Integer)
            render(text: "ok", status: 200)
        } catch (Exception ignored) {
            render(text: "not ready", status: 503)
        }
    }
}
