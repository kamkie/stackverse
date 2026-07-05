package dev.stackverse.backend;

import io.micronaut.context.annotation.Factory;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashMap;
import java.util.Map;

@Factory
final class FlywayMigrationLogging {
    @Singleton
    @Named("default")
    Callback[] flywayCallbacks() {
        return new Callback[]{new MigrationLoggingCallback()};
    }

    private static final class MigrationLoggingCallback implements Callback {
        private static final Logger LOG = LoggerFactory.getLogger(MigrationLoggingCallback.class);

        @Override
        public boolean supports(Event event, Context context) {
            return Event.AFTER_EACH_MIGRATE.equals(event);
        }

        @Override
        public boolean canHandleInTransaction(Event event, Context context) {
            return true;
        }

        @Override
        public void handle(Event event, Context context) {
            MigrationInfo info = context.getMigrationInfo();
            if (info == null) {
                return;
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("version", info.getVersion() == null ? null : info.getVersion().getVersion());
            fields.put("description", info.getDescription());
            EventLog.info(LOG, "db_migration_applied", "success",
                    "Applied database migration " + info.getScript(), fields);
        }

        @Override
        public String getCallbackName() {
            return MigrationLoggingCallback.class.getSimpleName();
        }
    }
}
