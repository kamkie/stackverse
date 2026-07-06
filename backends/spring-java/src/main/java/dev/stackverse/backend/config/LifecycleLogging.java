package dev.stackverse.backend.config;

import com.zaxxer.hikari.HikariDataSource;
import dev.stackverse.backend.common.Logging;
import javax.sql.DataSource;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.context.event.EventListener;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
public class LifecycleLogging {
    private final Environment environment;
    private final DataSource dataSource;
    private final org.slf4j.Logger log = LoggerFactory.getLogger(getClass());

    public LifecycleLogging(Environment environment, DataSource dataSource) {
        this.environment = environment;
        this.dataSource = dataSource;
    }

    @EventListener
    public void onReady(ApplicationReadyEvent event) {
        String jdbcUrl = dataSource instanceof HikariDataSource hikari ? hikari.getJdbcUrl() : null;
        Logging.logEvent(
            log,
            Level.INFO,
            "application_start",
            "success",
            "Stackverse backend is up and accepting requests",
            "port", environment.getProperty("server.port"),
            "db_url", jdbcUrl == null ? null : jdbcUrl.split("\\?", 2)[0],
            "oidc_issuer_uri", environment.getProperty("stackverse.oidc.issuer-uri"),
            "oidc_jwks_uri", blankToNull(environment.getProperty("stackverse.oidc.jwks-uri")),
            "seed_messages_dir", environment.getProperty("stackverse.seed.messages-dir"),
            "log_level", environment.getProperty("logging.level.root"),
            "log_format", environment.getProperty("LOG_FORMAT", "json"),
            "otel_sdk_disabled", environment.getProperty("OTEL_SDK_DISABLED", "true")
        );
    }

    @EventListener
    public void onClosed(ContextClosedEvent event) {
        Logging.logEvent(log, Level.INFO, "application_stop", "success", "Stackverse backend shutting down");
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
