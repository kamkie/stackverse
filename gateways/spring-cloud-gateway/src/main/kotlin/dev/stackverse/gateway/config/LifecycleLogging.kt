package dev.stackverse.gateway.config

import dev.stackverse.gateway.common.logEvent
import org.slf4j.LoggerFactory
import org.slf4j.event.Level
import org.springframework.boot.context.event.ApplicationReadyEvent
import org.springframework.context.event.ContextClosedEvent
import org.springframework.context.event.EventListener
import org.springframework.core.env.Environment
import org.springframework.stereotype.Component
import java.net.URI

/**
 * Lifecycle contract events (docs/LOGGING.md §5): `application_start` once the
 * service is accepting traffic — with the effective configuration, secrets
 * excluded — and `application_stop` on orderly shutdown, so restarts stay
 * distinguishable from crashes (a killed process never emits it).
 */
@Component
class LifecycleLogging(private val environment: Environment, private val gateway: GatewayProperties) {

    private val log = LoggerFactory.getLogger(javaClass)

    @EventListener
    fun onReady(event: ApplicationReadyEvent) {
        log.logEvent(
            Level.INFO, "application_start", "success", "Stackverse gateway is up and accepting requests",
            "port" to environment.getProperty("server.port"),
            "backend_url" to gateway.backendUrl,
            "frontend_url" to gateway.frontendUrl,
            "public_url" to gateway.publicUrl,
            "redis_endpoint" to redisEndpoint(environment.getProperty("spring.data.redis.url")),
            "oidc_issuer_uri" to gateway.oidc.issuerUri,
            "oidc_internal_issuer_uri" to gateway.oidc.internalIssuerUri.takeIf { it != gateway.oidc.issuerUri },
            "oidc_client_id" to gateway.oidc.clientId,
            "log_level" to environment.getProperty("logging.level.root"),
            "log_format" to environment.getProperty("LOG_FORMAT", "json"),
            "otel_sdk_disabled" to environment.getProperty("OTEL_SDK_DISABLED", "true"),
        )
    }

    @EventListener
    fun onClosed(event: ContextClosedEvent) {
        log.logEvent(Level.INFO, "application_stop", "success", "Stackverse gateway shutting down")
    }

    /** Endpoint only: a REDIS_URL may carry credentials in its userinfo part (§6). */
    private fun redisEndpoint(url: String?): String? = url?.let {
        runCatching {
            val uri = URI(it)
            "${uri.scheme}://${uri.host}:${if (uri.port > 0) uri.port else 6379}"
        }.getOrDefault("(unparseable)")
    }
}
