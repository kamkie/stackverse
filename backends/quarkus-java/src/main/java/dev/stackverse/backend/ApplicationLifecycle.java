package dev.stackverse.backend;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.flyway.FlywayConfigurationCustomizer;
import io.quarkus.runtime.ShutdownEvent;
import io.quarkus.runtime.StartupEvent;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.flywaydb.core.api.callback.Callback;
import org.flywaydb.core.api.callback.Context;
import org.flywaydb.core.api.callback.Event;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.jboss.logging.Logger;

@ApplicationScoped
public class ApplicationLifecycle {
    private static final Logger LOG = Logger.getLogger(ApplicationLifecycle.class);

    private final DataSource dataSource;
    private final ObjectMapper mapper;
    private final String seedMessagesDir;
    private final boolean seedMessagesEnabled;

    @Inject
    public ApplicationLifecycle(
            DataSource dataSource,
            ObjectMapper mapper,
            @ConfigProperty(name = "stackverse.seed.messages-dir") String seedMessagesDir,
            @ConfigProperty(name = "stackverse.seed.enabled", defaultValue = "true")
                    boolean seedMessagesEnabled) {
        this.dataSource = dataSource;
        this.mapper = mapper;
        this.seedMessagesDir = seedMessagesDir;
        this.seedMessagesEnabled = seedMessagesEnabled;
    }

    void onStart(@Observes StartupEvent event) {
        if (seedMessagesEnabled) {
            seedMessages();
        }
        StackverseLog.event(
                LOG,
                Logger.Level.INFO,
                "application_start",
                "success",
                "Stackverse Quarkus backend started",
                Map.of("component", "backend", "stack", "quarkus-java"));
    }

    void onStop(@Observes ShutdownEvent event) {
        StackverseLog.event(
                LOG,
                Logger.Level.INFO,
                "application_stop",
                "success",
                "Stackverse Quarkus backend stopped",
                Map.of("component", "backend"));
    }

    void seedMessages() {
        Path dir = Path.of(seedMessagesDir);
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException(
                    "Message seed directory not found: "
                            + dir
                            + " - set SEED_MESSAGES_DIR to the spec/messages directory");
        }
        try (var stream = Files.list(dir)) {
            List<Path> files =
                    stream.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .sorted()
                            .toList();
            for (Path file : files) {
                seedFile(file);
            }
        } catch (IOException error) {
            throw new IllegalStateException("Unable to read message seed directory: " + dir, error);
        }
    }

    private void seedFile(Path file) {
        String filename = file.getFileName().toString();
        String language = filename.substring(0, filename.length() - ".json".length());
        try {
            Map<String, String> entries = mapper.readValue(file.toFile(), new TypeReference<>() {});
            int inserted = 0;
            try (Connection connection = dataSource.getConnection();
                    PreparedStatement statement =
                            connection.prepareStatement(
                                    "insert into messages (id, key, language, text, created_at, updated_at)"
                                            + " values (?, ?, ?, ?, ?, ?)"
                                            + " on conflict (key, language) do nothing")) {
                for (Map.Entry<String, String> entry : entries.entrySet()) {
                    statement.setObject(1, UUID.randomUUID());
                    statement.setString(2, entry.getKey());
                    statement.setString(3, language);
                    statement.setString(4, entry.getValue());
                    statement.setTimestamp(5, Timestamp.from(ServiceSupport.now()));
                    statement.setTimestamp(6, Timestamp.from(ServiceSupport.now()));
                    inserted += statement.executeUpdate();
                }
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("language", language);
            fields.put("inserted", inserted);
            fields.put("skipped", entries.size() - inserted);
            StackverseLog.event(
                    LOG,
                    Logger.Level.INFO,
                    "message_seed_imported",
                    "success",
                    "Message seed '" + language + "' imported",
                    fields);
        } catch (IOException | SQLException error) {
            throw new IllegalStateException("Unable to import message seed " + file, error);
        }
    }
}

@ApplicationScoped
class MigrationLogging implements FlywayConfigurationCustomizer {
    private static final Logger LOG = Logger.getLogger(MigrationLogging.class);

    @Override
    public void customize(FluentConfiguration configuration) {
        configuration.callbacks(
                new Callback() {
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
                        String migration =
                                context.getMigrationInfo() == null
                                        ? "unknown"
                                        : context.getMigrationInfo().getScript();
                        StackverseLog.event(
                                LOG,
                                Logger.Level.INFO,
                                "db_migration_applied",
                                "success",
                                "Applied migration " + migration,
                                Map.of("migration", migration));
                    }

                    @Override
                    public String getCallbackName() {
                        return "stackverse-migration-logging";
                    }
                });
    }
}
