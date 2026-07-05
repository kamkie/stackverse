package dev.stackverse.backend;

import io.micronaut.context.annotation.Value;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.runtime.server.event.ServerShutdownEvent;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

@Singleton
final class LifecycleEvents implements ApplicationEventListener<Object> {
    private static final Logger LOG = LoggerFactory.getLogger(LifecycleEvents.class);

    private final String dbHost;
    private final String dbName;
    private final String issuer;

    LifecycleEvents(
            @Value("${datasources.default.url}") String jdbcUrl,
            @Value("${datasources.default.username}") String dbUser,
            @Value("${stackverse.oidc.issuer-uri}") String issuer
    ) {
        this.dbHost = jdbcUrl.replaceAll(".*//([^/:]+).*", "$1");
        this.dbName = jdbcUrl.replaceAll(".*/([^/?]+).*", "$1");
        this.issuer = issuer;
    }

    @Override
    public void onApplicationEvent(Object event) {
        if (event instanceof ServerStartupEvent) {
            EventLog.info(LOG, "application_start", "success", "Micronaut backend started",
                    Map.of("db_host", dbHost, "db_name", dbName, "oidc_issuer", issuer));
        } else if (event instanceof ServerShutdownEvent) {
            EventLog.info(LOG, "application_stop", "success", "Micronaut backend stopped");
        }
    }

    @Override
    public boolean supports(Object event) {
        return event instanceof ServerStartupEvent || event instanceof ServerShutdownEvent;
    }
}
