package dev.stackverse.backend.message;

import static dev.stackverse.backend.common.Time.nowUtc;

import dev.stackverse.backend.audit.AuditService;
import dev.stackverse.backend.common.ConflictProblem;
import dev.stackverse.backend.common.Logging;
import dev.stackverse.backend.common.NotFoundProblem;
import dev.stackverse.backend.common.Validator;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.LoggerFactory;
import org.slf4j.event.Level;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class MessageService {
    private static final Pattern KEY_PATTERN = Pattern.compile("^[a-z0-9-]+(\\.[a-z0-9-]+)*$");
    private static final Pattern LANGUAGE_PATTERN = Pattern.compile("^[a-z]{2}$");

    private final MessageRepository repository;
    private final AuditService auditService;
    private final org.slf4j.Logger log = LoggerFactory.getLogger(getClass());

    public MessageService(MessageRepository repository, AuditService auditService) {
        this.repository = repository;
        this.auditService = auditService;
    }

    public Message create(String actor, MessageRequest request) {
        ValidatedMessage input = validate(request);
        if (repository.existsByKeyAndLanguage(input.key(), input.language())) {
            throw duplicateConflict(input);
        }
        Instant now = nowUtc();
        try {
            Message message = repository.saveAndFlush(new Message(input.key(), input.language(), input.text(), input.description(), now, now));
            auditService.record(actor, "message.created", "message", message.getId().toString(), snapshot(message));
            logMessageEvent("message_created", "Message created", actor, message);
            return message;
        } catch (DataIntegrityViolationException exception) {
            throw duplicateConflict(input);
        }
    }

    public Message update(String actor, UUID id, MessageRequest request) {
        Message message = repository.findById(id).orElseThrow(NotFoundProblem::new);
        ValidatedMessage input = validate(request);
        Message duplicate = repository.findByKeyAndLanguage(input.key(), input.language());
        if (duplicate != null && !duplicate.getId().equals(message.getId())) {
            throw duplicateConflict(input);
        }
        message.setKey(input.key());
        message.setLanguage(input.language());
        message.setText(input.text());
        message.setDescription(input.description());
        message.setUpdatedAt(nowUtc());
        try {
            repository.flush();
        } catch (DataIntegrityViolationException exception) {
            throw duplicateConflict(input);
        }
        auditService.record(actor, "message.updated", "message", message.getId().toString(), snapshot(message));
        logMessageEvent("message_updated", "Message updated", actor, message);
        return message;
    }

    public void delete(String actor, UUID id) {
        Message message = repository.findById(id).orElseThrow(NotFoundProblem::new);
        repository.delete(message);
        auditService.record(actor, "message.deleted", "message", message.getId().toString(), snapshot(message));
        logMessageEvent("message_deleted", "Message deleted", actor, message);
    }

    @Transactional(readOnly = true)
    public Map<String, String> bundle(String language) {
        Map<String, String> texts = new TreeMap<>();
        Set<String> languages = LanguageResolver.DEFAULT_LANGUAGE.equals(language)
            ? Set.of(LanguageResolver.DEFAULT_LANGUAGE)
            : Set.of(LanguageResolver.DEFAULT_LANGUAGE, language);
        for (Message message : repository.findByLanguageIn(languages)) {
            if (message.getLanguage().equals(language) || !texts.containsKey(message.getKey())) {
                texts.put(message.getKey(), message.getText());
            }
        }
        return texts;
    }

    private void logMessageEvent(String event, String description, String actor, Message message) {
        Logging.logEvent(
            log,
            Level.INFO,
            event,
            "success",
            description,
            "actor", actor,
            "resource_type", "message",
            "resource_id", message.getId().toString(),
            "message_key", message.getKey(),
            "language", message.getLanguage()
        );
    }

    private Map<String, Object> snapshot(Message message) {
        Map<String, Object> snapshot = new LinkedHashMap<>();
        snapshot.put("key", message.getKey());
        snapshot.put("language", message.getLanguage());
        snapshot.put("text", message.getText());
        snapshot.put("description", message.getDescription());
        return snapshot;
    }

    private ConflictProblem duplicateConflict(ValidatedMessage input) {
        return new ConflictProblem("A message with key '" + input.key() + "' and language '" + input.language() + "' already exists.");
    }

    private ValidatedMessage validate(MessageRequest request) {
        Validator validator = new Validator();
        String key = request.key() == null ? "" : request.key().trim();
        validator.check(KEY_PATTERN.matcher(key).matches() && key.length() <= 150, "key", "validation.message.key.invalid");
        String language = request.language() == null ? "" : request.language().trim();
        validator.check(LANGUAGE_PATTERN.matcher(language).matches(), "language", "validation.message.language.invalid");
        String text = request.text() == null ? "" : request.text();
        validator.check(!text.isEmpty(), "text", "validation.message.text.required");
        validator.check(text.length() <= 2000, "text", "validation.message.text.too-long");
        validator.check(request.description() == null || request.description().length() <= 1000, "description", "validation.message.description.too-long");
        validator.throwIfInvalid();
        return new ValidatedMessage(key, language, text, request.description());
    }

    private record ValidatedMessage(String key, String language, String text, String description) {
    }
}
