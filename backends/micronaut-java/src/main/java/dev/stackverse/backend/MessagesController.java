package dev.stackverse.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Controller
final class MessagesController {
    private static final Logger LOG = LoggerFactory.getLogger(MessagesController.class);

    private final Database db;
    private final MessageCatalog messages;
    private final AuditService audit;
    private final SecuritySupport security;
    private final ObjectMapper mapper;

    MessagesController(Database db, MessageCatalog messages, AuditService audit, SecuritySupport security, ObjectMapper mapper) {
        this.db = db;
        this.messages = messages;
        this.audit = audit;
        this.security = security;
        this.mapper = mapper;
    }

    @Get("/api/v1/messages")
    MutableHttpResponse<?> list(HttpRequest<?> request) {
        int page = WebSupport.page(request);
        int size = WebSupport.size(request);
        String key = request.getParameters().getFirst("key").orElse("");
        String language = request.getParameters().getFirst("language").orElse("");
        String q = request.getParameters().getFirst("q").orElse("");
        if (WebSupport.length(q) > 200) {
            throw Problems.badRequest("q is too long");
        }
        List<MessageResponse> items = messages.search(key, language, q, page, size).stream().map(MessageResponse::from).toList();
        long total = messages.countSearch(key, language, q);
        return WebSupport.etag(mapper, request, WebSupport.pageResponse(items, page, size, total), null);
    }

    @Get("/api/v1/messages/bundle")
    MutableHttpResponse<?> bundle(HttpRequest<?> request) {
        String language = messages.resolve(request);
        MessageBundle bundle = new MessageBundle(language, messages.bundle(language));
        return WebSupport.etag(mapper, request, bundle, language);
    }

    @Get("/api/v1/messages/{id}")
    MutableHttpResponse<?> get(HttpRequest<?> request, @PathVariable String id) {
        Message message = messages.byId(WebSupport.uuid(id, "id"));
        return WebSupport.etag(mapper, request, MessageResponse.from(message), null);
    }

    @Post("/api/v1/messages")
    MutableHttpResponse<MessageResponse> create(HttpRequest<?> request, @Body MessageInput body) {
        Identity actor = security.requireRole(request, "admin");
        ValidMessage input = validate(body);
        if (messages.conflicting(input.key(), input.language(), new UUID(0, 0))) {
            throw Problems.conflict("A message with key '" + input.key() + "' and language '" + input.language() + "' already exists.");
        }
        Instant now = WebSupport.now();
        Message message = new Message(UUID.randomUUID(), input.key(), input.language(), input.text(), input.description(), now, now);
        db.inTx(connection -> {
            try {
                messages.insert(connection, message);
                audit.record(connection, actor.username(), "message.created", "message", message.id().toString(),
                        Map.of("key", message.key(), "language", message.language(), "text", message.text()));
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
            return null;
        });
        EventLog.info(LOG, "message_created", "success", "Message created",
                Map.of("actor", actor.username(), "resource_type", "message", "resource_id", message.id().toString(),
                        "message_key", message.key(), "language", message.language()));
        return WebSupport.created("/api/v1/messages/" + message.id(), MessageResponse.from(message));
    }

    @Put("/api/v1/messages/{id}")
    MessageResponse update(HttpRequest<?> request, @PathVariable String id, @Body MessageInput body) {
        Identity actor = security.requireRole(request, "admin");
        UUID messageId = WebSupport.uuid(id, "id");
        Message existing = messages.byId(messageId);
        ValidMessage input = validate(body);
        if (messages.conflicting(input.key(), input.language(), existing.id())) {
            throw Problems.conflict("A message with key '" + input.key() + "' and language '" + input.language() + "' already exists.");
        }
        Message updated = new Message(existing.id(), input.key(), input.language(), input.text(), input.description(),
                existing.createdAt(), WebSupport.now());
        db.inTx(connection -> {
            try {
                messages.update(connection, updated);
                audit.record(connection, actor.username(), "message.updated", "message", updated.id().toString(),
                        Map.of("key", updated.key(), "language", updated.language(), "text", updated.text()));
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
            return null;
        });
        EventLog.info(LOG, "message_updated", "success", "Message updated",
                Map.of("actor", actor.username(), "resource_type", "message", "resource_id", updated.id().toString(),
                        "message_key", updated.key(), "language", updated.language()));
        return MessageResponse.from(updated);
    }

    @Delete("/api/v1/messages/{id}")
    HttpResponse<?> delete(HttpRequest<?> request, @PathVariable String id) {
        Identity actor = security.requireRole(request, "admin");
        UUID messageId = WebSupport.uuid(id, "id");
        Message existing = messages.byId(messageId);
        db.inTx(connection -> {
            try {
                messages.delete(connection, existing.id());
                audit.record(connection, actor.username(), "message.deleted", "message", existing.id().toString(),
                        Map.of("key", existing.key(), "language", existing.language()));
            } catch (SQLException ex) {
                throw new IllegalStateException(ex);
            }
            return null;
        });
        EventLog.info(LOG, "message_deleted", "success", "Message deleted",
                Map.of("actor", actor.username(), "resource_type", "message", "resource_id", existing.id().toString(),
                        "message_key", existing.key(), "language", existing.language()));
        return HttpResponse.noContent();
    }

    private ValidMessage validate(MessageInput body) {
        Validator validator = new Validator();
        String key = WebSupport.trim(body == null ? null : body.key());
        validator.check(WebSupport.KEY_PATTERN.matcher(key).matches() && WebSupport.length(key) <= 150,
                "key", "validation.message.key.invalid");
        String language = WebSupport.trim(body == null ? null : body.language());
        validator.check(WebSupport.LANGUAGE_PATTERN.matcher(language).matches(), "language", "validation.message.language.invalid");
        String text = body == null ? null : body.text();
        validator.check(text != null && !text.isEmpty(), "text", "validation.message.text.required");
        validator.check(WebSupport.length(text) <= 2000, "text", "validation.message.text.too-long");
        String description = body == null ? null : body.description();
        validator.check(WebSupport.length(description) <= 1000, "description", "validation.message.description.too-long");
        validator.throwIfInvalid();
        return new ValidMessage(key, language, text, description);
    }

    private record ValidMessage(String key, String language, String text, String description) {
    }
}
