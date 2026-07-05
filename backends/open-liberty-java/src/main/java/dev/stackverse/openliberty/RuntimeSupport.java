package dev.stackverse.openliberty;

import com.fasterxml.jackson.core.type.TypeReference;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
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
import java.util.concurrent.atomic.AtomicBoolean;
import javax.sql.DataSource;
import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.SpanContext;
import org.flywaydb.core.Flyway;

final class RuntimeSupport {
  static final Config CONFIG = Config.fromEnv();
  private static final AtomicBoolean BOOTED = new AtomicBoolean(false);
  private static HikariDataSource dataSource;

  private RuntimeSupport() {}

  static void boot() {
    if (BOOTED.get()) {
      return;
    }
    synchronized (RuntimeSupport.class) {
      if (BOOTED.get()) {
        return;
      }
      HikariDataSource candidate = createDataSource(CONFIG);
      try {
        dataSource = candidate;
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate();
        Log.event("info", "db_migration_applied", "success", "Database migrations applied", Map.of());
        seedMessages();
        Log.event("info", "application_start", "success", "Stackverse Open Liberty backend started",
            Map.of("port", CONFIG.port(), "db_host", CONFIG.dbHost(), "oidc_issuer_uri", CONFIG.issuerUri()));
        BOOTED.set(true);
      } catch (RuntimeException | Error ex) {
        candidate.close();
        dataSource = null;
        throw ex;
      }
    }
  }

  static DataSource dataSource() {
    boot();
    return dataSource;
  }

  static Connection connection() throws SQLException {
    return dataSource().getConnection();
  }

  static PreparedStatement prepare(Connection connection, String sql, Object... params) throws SQLException {
    PreparedStatement statement = connection.prepareStatement(sql);
    for (int i = 0; i < params.length; i++) {
      bind(statement, i + 1, params[i], connection);
    }
    return statement;
  }

  static void bind(PreparedStatement statement, int index, Object value, Connection connection) throws SQLException {
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

  static <T> T transaction(SqlFunction<Connection, T> work) {
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

  private static HikariDataSource createDataSource(Config config) {
    HikariConfig hikari = new HikariConfig();
    hikari.setJdbcUrl("jdbc:postgresql://" + config.dbHost() + ":" + config.dbPort() + "/" + config.dbName());
    hikari.setUsername(config.dbUser());
    hikari.setPassword(config.dbPassword());
    hikari.setMaximumPoolSize(4);
    hikari.setMinimumIdle(0);
    hikari.setPoolName("stackverse-open-liberty-java");
    return new HikariDataSource(hikari);
  }

  private static void seedMessages() {
    Path dir = CONFIG.seedMessagesDir();
    if (!Files.isDirectory(dir)) {
      throw new IllegalStateException("Message seed directory not found: " + dir);
    }
    TypeReference<Map<String, String>> type = new TypeReference<>() {};
    try (var files = Files.list(dir)) {
      for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json"))
          .sorted(Comparator.comparing(path -> path.getFileName().toString()))
          .toList()) {
        String language = file.getFileName().toString().replaceFirst("\\.json$", "");
        Map<String, String> entries = JsonSupport.MAPPER.readValue(Files.readString(file), type);
        int inserted = 0;
        try (Connection connection = dataSource.getConnection()) {
          for (Map.Entry<String, String> entry : entries.entrySet()) {
            try (PreparedStatement statement = prepare(connection,
                """
                insert into messages (id, key, language, text, created_at, updated_at)
                values (?, ?, ?, ?, ?, ?)
                on conflict (key, language) do nothing
                """,
                UUID.randomUUID(), entry.getKey(), language, entry.getValue(), Instant.now(), Instant.now())) {
              inserted += statement.executeUpdate();
            }
          }
        }
        Log.event("info", "message_seed_imported", "success", "Message seed imported",
            Map.of("language", language, "inserted", inserted, "skipped", entries.size() - inserted));
      }
    } catch (IOException | SQLException ex) {
      throw new IllegalStateException("Message seed import failed", ex);
    }
  }

  interface SqlFunction<T, R> {
    R apply(T value) throws SQLException;
  }

  record Config(
      int port,
      String dbHost,
      int dbPort,
      String dbName,
      String dbUser,
      String dbPassword,
      String issuerUri,
      String jwksUri,
      Path seedMessagesDir,
      String logLevel,
      String logFormat,
      boolean otelEnabled) {
    static Config fromEnv() {
      return new Config(
          intEnv("PORT", 8080),
          env("DB_HOST", "localhost"),
          intEnv("DB_PORT", 5432),
          env("DB_NAME", "stackverse"),
          env("DB_USER", "stackverse"),
          env("DB_PASSWORD", "stackverse"),
          stripTrailingSlash(env("OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse")),
          blankToNull(System.getenv("OIDC_JWKS_URI")),
          seedDir(),
          env("LOG_LEVEL", "info").toLowerCase(),
          env("LOG_FORMAT", "json").toLowerCase(),
          "false".equalsIgnoreCase(env("OTEL_SDK_DISABLED", "true")));
    }

    private static String env(String name, String fallback) {
      String value = System.getenv(name);
      return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int intEnv(String name, int fallback) {
      return Integer.parseInt(env(name, Integer.toString(fallback)));
    }

    private static String blankToNull(String value) {
      return value == null || value.isBlank() ? null : value.trim();
    }

    private static String stripTrailingSlash(String value) {
      return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static Path seedDir() {
      String configured = System.getenv("SEED_MESSAGES_DIR");
      if (configured != null && !configured.isBlank()) {
        return Path.of(configured.trim());
      }
      Path repoRelative = Path.of("../../spec/messages").toAbsolutePath().normalize();
      if (Files.isDirectory(repoRelative)) {
        return repoRelative;
      }
      return Path.of("spec/messages").toAbsolutePath().normalize();
    }
  }
}

final class Log {
  private Log() {}

  static void event(String level, String event, String outcome, String message, Map<String, ?> fields) {
    if (!enabled(level)) {
      return;
    }
    Map<String, Object> line = new java.util.LinkedHashMap<>();
    line.put("timestamp", Instant.now().toString());
    line.put("level", level.toUpperCase());
    line.put("logger", "stackverse.open-liberty-java");
    line.put("message", message);
    line.put("event", event);
    line.put("outcome", outcome);
    SpanContext spanContext = Span.current().getSpanContext();
    if (spanContext.isValid()) {
      line.put("trace_id", spanContext.getTraceId());
      line.put("span_id", spanContext.getSpanId());
    }
    fields.forEach(line::put);
    if ("text".equals(RuntimeSupport.CONFIG.logFormat())) {
      System.out.println(line);
    } else {
      System.out.println(JsonSupport.jsonString(line));
    }
  }

  private static boolean enabled(String level) {
    return severity(level) <= severity(RuntimeSupport.CONFIG.logLevel());
  }

  private static int severity(String level) {
    return switch (level.toLowerCase()) {
      case "error", "fatal" -> 0;
      case "warn" -> 1;
      case "debug" -> 3;
      default -> 2;
    };
  }
}
