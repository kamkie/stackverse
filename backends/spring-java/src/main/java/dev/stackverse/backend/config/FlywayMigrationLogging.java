package dev.stackverse.backend.config;

import dev.stackverse.backend.common.Logging;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.stereotype.Component;

@Component
public class FlywayMigrationLogging implements Callback {
    private final org.slf4j.Logger log = LoggerFactory.getLogger(getClass());

    @Override
    public boolean supports(Event event, Context context) {
        return event == Event.AFTER_EACH_MIGRATE;
    }

    @Override
    public boolean canHandleInTransaction(Event event, Context context) {
        return true;
    }

    @Override
    public void handle(Event event, Context context) {
        var info = context.getMigrationInfo();
        if (info == null) {
            return;
        }
        Logging.logEvent(
            log,
            Level.INFO,
            "db_migration_applied",
            "success",
            "Applied database migration " + info.getVersion() + " - " + info.getDescription(),
            "version", info.getVersion() == null ? null : info.getVersion().getVersion(),
            "description", info.getDescription()
        );
    }

    @Override
    public String getCallbackName() {
        return getClass().getSimpleName();
    }
}
