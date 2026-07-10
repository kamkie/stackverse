package dev.stackverse.openliberty;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.context.Initialized;
import jakarta.enterprise.event.Observes;
import jakarta.inject.Inject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;

/**
 * Container-managed JDBC boundary for the variant.
 *
 * <p>Stackverse deliberately keeps SQL explicit so the same contract can be compared across stacks.
 * The pool, migrations, transactions, and statement binding are nevertheless owned by one injected
 * application-scoped bean instead of static process state.
 */
@ApplicationScoped
public class RuntimeSupport {
    @Inject AppConfig config;
    @Inject EventLogger log;

    private HikariDataSource dataSource;

    /** Forces this normal-scoped bean to initialize while CDI starts the application context. */
    void onApplicationStart(@Observes @Initialized(ApplicationScoped.class) Object event) {
        // Receiving the observer requires CDI to create this bean, so @PostConstruct has completed.
    }

    @PostConstruct
    void start() {
        long startedAt = System.nanoTime();
        HikariDataSource candidate = null;
        try {
            candidate = createDataSource();
            dataSource = candidate;
            Flyway.configure()
                    .dataSource(dataSource)
                    .locations("classpath:db/migration")
                    .load()
                    .migrate();
            log.event(
                    "info",
                    "db_migration_applied",
                    "success",
                    "Database migrations applied",
                    Map.of());
            seedMessages();
            log.event(
                    "info",
                    "application_start",
                    "success",
                    "Stackverse Open Liberty backend started",
                    Map.of(
                            "port", config.port(),
                            "db_host", config.dbHost(),
                            "oidc_issuer_uri", config.issuerUri()));
        } catch (RuntimeException | Error ex) {
            long durationMs = EventLogger.elapsedMillis(startedAt);
            if (EventLogger.causedBySqlFailure(ex)) {
                log.dependencyFailure("postgres", ex, durationMs);
            }
            log.fatal(
                    "application_start",
                    "failure",
                    "Stackverse Open Liberty backend failed to start",
                    ex,
                    Map.of("duration_ms", durationMs, "error_code", EventLogger.errorCode(ex)));
            if (candidate != null) {
                candidate.close();
            }
            dataSource = null;
            throw ex;
        }
    }

    @PreDestroy
    void stop() {
        log.event(
                "info",
                "application_stop",
                "success",
                "Stackverse Open Liberty backend stopped",
                Map.of());
        if (dataSource != null) {
            dataSource.close();
        }
    }

    Connection connection() throws SQLException {
        return dataSource.getConnection();
    }

    PreparedStatement prepare(Connection connection, String sql, Object... params)
            throws SQLException {
        PreparedStatement statement = connection.prepareStatement(sql);
        try {
            for (int i = 0; i < params.length; i++) {
                bind(statement, i + 1, params[i], connection);
            }
            return statement;
        } catch (SQLException | RuntimeException ex) {
            try {
                statement.close();
            } catch (SQLException closeFailure) {
                ex.addSuppressed(closeFailure);
            }
            throw ex;
        }
    }

    void bind(PreparedStatement statement, int index, Object value, Connection connection)
            throws SQLException {
        if (value == null) {
            statement.setObject(index, null);
        } else if (value instanceof Instant instant) {
            statement.setTimestamp(index, Timestamp.from(instant));
        } else if (value instanceof UUID uuid) {
            statement.setObject(index, uuid);
        } else if (value instanceof String[] array) {
            statement.setArray(index, connection.createArrayOf("text", array));
        } else {
            statement.setObject(index, value);
        }
    }

    <T> T transaction(SqlFunction<Connection, T> work) {
        try (Connection connection = connection()) {
            boolean previous = connection.getAutoCommit();
            connection.setAutoCommit(false);
            try {
                T result = work.apply(connection);
                connection.commit();
                return result;
            } catch (Throwable ex) {
                connection.rollback();
                throw ex;
            } finally {
                connection.setAutoCommit(previous);
            }
        } catch (ApiProblem | ValidationProblem ex) {
            throw ex;
        } catch (SQLException ex) {
            throw new RuntimeException(ex);
        }
    }

    private HikariDataSource createDataSource() {
        HikariConfig hikari = new HikariConfig();
        hikari.setJdbcUrl(
                "jdbc:postgresql://"
                        + config.dbHost()
                        + ":"
                        + config.dbPort()
                        + "/"
                        + config.dbName());
        hikari.setUsername(config.dbUser());
        hikari.setPassword(config.dbPassword());
        hikari.setMaximumPoolSize(4);
        hikari.setMinimumIdle(0);
        hikari.setPoolName("stackverse-open-liberty-java");
        return new HikariDataSource(hikari);
    }

    private void seedMessages() {
        Path dir = config.seedMessagesDir();
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Message seed directory not found: " + dir);
        }
        TypeReference<Map<String, String>> type = new TypeReference<>() {};
        try (var files = Files.list(dir)) {
            for (Path file :
                    files.filter(path -> path.getFileName().toString().endsWith(".json"))
                            .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                            .toList()) {
                String language = file.getFileName().toString().replaceFirst("\\.json$", "");
                Map<String, String> entries =
                        JsonSupport.MAPPER.readValue(Files.readString(file), type);
                int inserted = 0;
                try (Connection connection = dataSource.getConnection()) {
                    for (Map.Entry<String, String> entry : entries.entrySet()) {
                        try (PreparedStatement statement =
                                prepare(
                                        connection,
                                        """
                    insert into messages (id, key, language, text, created_at, updated_at)
                    values (?, ?, ?, ?, ?, ?)
                    on conflict (key, language) do nothing
                    """,
                                        UUID.randomUUID(),
                                        entry.getKey(),
                                        language,
                                        entry.getValue(),
                                        Instant.now(),
                                        Instant.now())) {
                            inserted += statement.executeUpdate();
                        }
                    }
                }
                log.event(
                        "info",
                        "message_seed_imported",
                        "success",
                        "Message seed imported",
                        Map.of(
                                "language", language,
                                "inserted", inserted,
                                "skipped", entries.size() - inserted));
            }
        } catch (IOException | SQLException ex) {
            throw new IllegalStateException("Message seed import failed", ex);
        }
    }

    interface SqlFunction<T, R> {
        R apply(T value) throws SQLException;
    }
}
