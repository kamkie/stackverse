package dev.stackverse.openliberty;

import jakarta.enterprise.context.ApplicationScoped;
import java.nio.file.Files;
import java.nio.file.Path;

/** Environment-backed application configuration, owned by CDI. */
@ApplicationScoped
public class AppConfig {
    private final int port = intEnv("PORT", 8080);
    private final String dbHost = env("DB_HOST", "localhost");
    private final int dbPort = intEnv("DB_PORT", 5432);
    private final String dbName = env("DB_NAME", "stackverse");
    private final String dbUser = env("DB_USER", "stackverse");
    private final String dbPassword = env("DB_PASSWORD", "stackverse");
    private final String issuerUri =
            env("OIDC_ISSUER_URI", "http://localhost:8180/realms/stackverse");
    private final Path seedMessagesDir = seedDir();
    private final String logLevel = env("LOG_LEVEL", "info").toLowerCase();
    private final String logFormat = env("LOG_FORMAT", "json").toLowerCase();

    int port() {
        return port;
    }

    String dbHost() {
        return dbHost;
    }

    int dbPort() {
        return dbPort;
    }

    String dbName() {
        return dbName;
    }

    String dbUser() {
        return dbUser;
    }

    String dbPassword() {
        return dbPassword;
    }

    String issuerUri() {
        return issuerUri;
    }

    Path seedMessagesDir() {
        return seedMessagesDir;
    }

    String logLevel() {
        return logLevel;
    }

    String logFormat() {
        return logFormat;
    }

    private static String env(String name, String fallback) {
        String value = System.getenv(name);
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    private static int intEnv(String name, int fallback) {
        return Integer.parseInt(env(name, Integer.toString(fallback)));
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
