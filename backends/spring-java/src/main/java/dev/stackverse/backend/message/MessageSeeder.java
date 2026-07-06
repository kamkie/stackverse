package dev.stackverse.backend.message;

import static dev.stackverse.backend.common.Time.nowUtc;

import dev.stackverse.backend.common.Logging;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.LinkedHashMap;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Component
public class MessageSeeder implements ApplicationRunner {
    private final MessageRepository repository;
    private final ObjectMapper objectMapper;
    private final SeedProperties properties;
    private final org.slf4j.Logger log = LoggerFactory.getLogger(getClass());

    public MessageSeeder(MessageRepository repository, ObjectMapper objectMapper, SeedProperties properties) {
        this.repository = repository;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        Path dir = Path.of(properties.messagesDir());
        if (!Files.isDirectory(dir)) {
            throw new IllegalStateException("Message seed directory not found: " + dir.toAbsolutePath() + " - set SEED_MESSAGES_DIR to the spec/messages directory");
        }
        try (var stream = Files.list(dir)) {
            for (Path file : stream.filter(path -> path.getFileName().toString().endsWith(".json")).sorted(Comparator.naturalOrder()).toList()) {
                seedLanguage(file);
            }
        }
    }

    private void seedLanguage(Path file) throws IOException {
        String filename = file.getFileName().toString();
        String language = filename.substring(0, filename.length() - ".json".length());
        StringMap entries = objectMapper.readValue(Files.readString(file), StringMap.class);
        var existing = repository.findByLanguage(language).stream().map(Message::getKey).collect(java.util.stream.Collectors.toSet());
        Instant now = nowUtc();
        var missing = entries.entrySet().stream()
            .filter(entry -> !existing.contains(entry.getKey()))
            .map(entry -> new Message(entry.getKey(), language, entry.getValue(), null, now, now))
            .toList();
        repository.saveAll(missing);
        Logging.logEvent(
            log,
            Level.INFO,
            "message_seed_imported",
            "success",
            "Message seed '" + language + "': " + missing.size() + " inserted, " + (entries.size() - missing.size()) + " already present",
            "language", language,
            "inserted", missing.size(),
            "skipped", entries.size() - missing.size()
        );
    }

    private static final class StringMap extends LinkedHashMap<String, String> {
    }
}
