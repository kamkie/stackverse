package dev.stackverse.backend;

import io.micronaut.context.annotation.Value;
import io.micronaut.runtime.event.annotation.EventListener;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

@Singleton
final class MessageCatalog {
    private static final Logger LOG = LoggerFactory.getLogger(MessageCatalog.class);
    private static final String DEFAULT_LANGUAGE = "en";

    private final Database db;
    private final ObjectMapper mapper;
    private final Path messagesDir;

    MessageCatalog(Database db, ObjectMapper mapper, @Value("${stackverse.seed.messages-dir}") String messagesDir) {
        this.db = db;
        this.mapper = mapper;
        this.messagesDir = Path.of(messagesDir);
    }

    @EventListener
    void seed(ServerStartupEvent ignored) {
        if (!Files.isDirectory(messagesDir)) {
            throw new IllegalStateException("Message seed directory does not exist: " + messagesDir.toAbsolutePath());
        }
        int inserted = db.inTx(connection -> {
            int count = 0;
            try (var files = Files.list(messagesDir)) {
                for (Path file : files.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                    String language = file.getFileName().toString().replaceFirst("\\.json$", "");
                    Map<String, String> messages = mapper.readValue(file.toFile(), new TypeReference<>() {
                    });
                    Instant now = WebSupport.now();
                    for (Map.Entry<String, String> entry : messages.entrySet()) {
                        count += db.update(connection,
                                """
                                insert into messages (id, key, language, text, description, created_at, updated_at)
                                values (?, ?, ?, ?, null, ?, ?)
                                on conflict (key, language) do nothing
                                """,
                                UUID.randomUUID(), entry.getKey(), language, entry.getValue(), now, now);
                    }
                }
            } catch (IOException ex) {
                throw new IllegalStateException(ex);
            }
            return count;
        });
        EventLog.info(LOG, "message_seed_imported", "success", "Message seed imported",
                Map.of("inserted", inserted, "directory", messagesDir.toString()));
    }

    String localize(io.micronaut.http.HttpRequest<?> request, String key) {
        String language = resolve(request);
        String text = textFor(key, language);
        if (text != null) {
            return text;
        }
        if (!DEFAULT_LANGUAGE.equals(language)) {
            text = textFor(key, DEFAULT_LANGUAGE);
            if (text != null) {
                return text;
            }
        }
        return key;
    }

    String resolve(io.micronaut.http.HttpRequest<?> request) {
        Set<String> supported = supportedLanguages();
        String explicit = request.getParameters().getFirst("lang").orElse("");
        if (!explicit.isBlank() && supported.contains(explicit)) {
            return explicit;
        }
        for (String code : WebSupport.acceptedLanguages(request.getHeaders().get("Accept-Language"))) {
            if (supported.contains(code)) {
                return code;
            }
        }
        return DEFAULT_LANGUAGE;
    }

    Map<String, String> bundle(String language) {
        List<MessageText> rows = db.query(
                "select key, language, text from messages where language in (?, ?)",
                rs -> new MessageText(rs.getString("key"), rs.getString("language"), rs.getString("text")),
                DEFAULT_LANGUAGE, language);
        Map<String, String> texts = new LinkedHashMap<>();
        for (MessageText row : rows) {
            if (language.equals(row.language())) {
                texts.put(row.key(), row.text());
            } else {
                texts.putIfAbsent(row.key(), row.text());
            }
        }
        return texts;
    }

    String textFor(String key, String language) {
        List<String> rows = db.query("select text from messages where key = ? and language = ?",
                rs -> rs.getString("text"), key, language);
        return rows.isEmpty() ? null : rows.getFirst();
    }

    Set<String> supportedLanguages() {
        return db.query("select distinct language from messages", rs -> rs.getString("language"))
                .stream()
                .map(language -> language.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());
    }

    List<Message> search(String key, String language, String q, int page, int size) {
        String qLike = q == null || q.isBlank() ? "" : "%" + WebSupport.escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
        return db.query("""
                select id, key, language, text, description, created_at, updated_at
                from messages
                where (? = '' or key = ?)
                  and (? = '' or language = ?)
                  and (? = '' or lower(key) like ? escape '\\' or lower(text) like ? escape '\\')
                order by key, language
                limit ? offset ?
                """, Models::message, key, key, language, language, qLike, qLike, qLike, size, WebSupport.offset(page, size));
    }

    long countSearch(String key, String language, String q) {
        String qLike = q == null || q.isBlank() ? "" : "%" + WebSupport.escapeLike(q.toLowerCase(Locale.ROOT)) + "%";
        return db.scalarLong("""
                select count(*)
                from messages
                where (? = '' or key = ?)
                  and (? = '' or language = ?)
                  and (? = '' or lower(key) like ? escape '\\' or lower(text) like ? escape '\\')
                """, key, key, language, language, qLike, qLike, qLike);
    }

    Message byId(UUID id) {
        return db.one("select id, key, language, text, description, created_at, updated_at from messages where id = ?",
                Models::message, id);
    }

    boolean conflicting(String key, String language, UUID excluding) {
        return db.scalarBoolean("select exists (select 1 from messages where key = ? and language = ? and id <> ?)",
                key, language, excluding);
    }

    void insert(Connection connection, Message message) throws SQLException {
        db.update(connection,
                "insert into messages (id, key, language, text, description, created_at, updated_at) values (?, ?, ?, ?, ?, ?, ?)",
                message.id(), message.key(), message.language(), message.text(), message.description(), message.createdAt(), message.updatedAt());
    }

    void update(Connection connection, Message message) throws SQLException {
        db.update(connection,
                "update messages set key = ?, language = ?, text = ?, description = ?, updated_at = ? where id = ?",
                message.key(), message.language(), message.text(), message.description(), message.updatedAt(), message.id());
    }

    void delete(Connection connection, UUID id) throws SQLException {
        db.update(connection, "delete from messages where id = ?", id);
    }

    private record MessageText(String key, String language, String text) {
    }
}
