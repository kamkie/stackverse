package dev.stackverse.backend;

import static dev.stackverse.backend.HttpResponses.pageResponse;
import static dev.stackverse.backend.PersistenceSupport.detail;
import static dev.stackverse.backend.PersistenceSupport.instant;
import static dev.stackverse.backend.PersistenceSupport.isUniqueViolation;
import static dev.stackverse.backend.PersistenceSupport.now;
import static dev.stackverse.backend.PersistenceSupport.params;
import static dev.stackverse.backend.PersistenceSupport.query;
import static dev.stackverse.backend.PersistenceSupport.queryOne;
import static dev.stackverse.backend.PersistenceSupport.scalarLong;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MessageService {
    private static final Logger LOG = Logger.getLogger(MessageService.class);

    private final DatabaseOperations database;
    private final Authorization authorization;
    private final RequestParameters requestParameters;
    private final HttpResponses httpResponses;
    private final AuditTrail auditTrail;
    private final Localizer localizer;

    public MessageService(
            DatabaseOperations database,
            Authorization authorization,
            RequestParameters requestParameters,
            HttpResponses httpResponses,
            AuditTrail auditTrail,
            Localizer localizer) {
        this.database = database;
        this.authorization = authorization;
        this.requestParameters = requestParameters;
        this.httpResponses = httpResponses;
        this.auditTrail = auditTrail;
        this.localizer = localizer;
    }

    public Response listMessages(RequestContext request) {
        int page = requestParameters.pagingPage(request);
        int size = requestParameters.pageSize(request);
        String key = requestParameters.singleParam(request, "key");
        String language = requestParameters.singleParam(request, "language");
        String q = requestParameters.singleParam(request, "q");
        requestParameters.maxLength(q, 200, "q");
        PageResponse<MessageResponse> response =
                database.withConnection(
                        connection -> {
                            SqlWhere where = new SqlWhere();
                            if (key != null) {
                                where.and("key = ?", key);
                            }
                            if (language != null) {
                                where.and("language = ?", language);
                            }
                            if (q != null && !q.isBlank()) {
                                where.and(
                                        "(key ilike ? escape '\\' or text ilike ? escape '\\')",
                                        "%" + requestParameters.escapeLike(q) + "%",
                                        "%" + requestParameters.escapeLike(q) + "%");
                            }
                            long total =
                                    scalarLong(
                                            connection,
                                            "select count(*) from messages " + where.sql(),
                                            where.params());
                            List<Object> params = new ArrayList<>(where.params());
                            params.add(size);
                            params.add(requestParameters.offset(page, size));
                            List<MessageResponse> items =
                                    query(
                                            connection,
                                            "select * from messages "
                                                    + where.sql()
                                                    + " order by key, language limit ? offset ?",
                                            params,
                                            rs -> messageResponse(message(rs)));
                            return pageResponse(items, page, size, total);
                        });
        return httpResponses.etag(request, response, null);
    }

    public Response messageBundle(RequestContext request) {
        String language = localizer.resolveLanguage(request.uriInfo(), request.headers());
        MessageBundleResponse body =
                new MessageBundleResponse(language, localizer.bundle(language));
        return httpResponses.etag(request, body, Map.of("Content-Language", language));
    }

    public Response getMessage(RequestContext request, String rawId) {
        UUID id = requestParameters.parseUuid(rawId);
        MessageResponse body =
                database.withConnection(
                        connection ->
                                queryOne(
                                                connection,
                                                "select * from messages where id = ?",
                                                List.of(id),
                                                rs -> messageResponse(message(rs)))
                                        .orElseThrow(StackverseProblem::notFound));
        return httpResponses.etag(request, body, null);
    }

    public Response createMessage(MessageInput input) {
        Caller caller = authorization.requireRole("admin");
        Message created =
                database.inTransaction(
                        connection -> {
                            Message inserted;
                            try {
                                Instant now = now();
                                inserted =
                                        queryOne(
                                                        connection,
                                                        "insert into messages (id, key, language, text, description, created_at, updated_at)"
                                                                + " values (?, ?, ?, ?, ?, ?, ?) returning *",
                                                        params(
                                                                UUID.randomUUID(),
                                                                input.key(),
                                                                input.language(),
                                                                input.text(),
                                                                input.description(),
                                                                now,
                                                                now),
                                                        MessageService::message)
                                                .orElseThrow();
                            } catch (RuntimeException error) {
                                if (isUniqueViolation(error)) {
                                    throw duplicateMessage(input);
                                }
                                throw error;
                            }
                            auditTrail.record(
                                    connection,
                                    caller.username(),
                                    "message.created",
                                    "message",
                                    inserted.id().toString(),
                                    snapshot(inserted));
                            return inserted;
                        });
        StackverseLog.event(
                LOG,
                Logger.Level.INFO,
                "message_created",
                "success",
                "Message created",
                Map.of(
                        "actor",
                        caller.username(),
                        "resource_type",
                        "message",
                        "resource_id",
                        created.id().toString(),
                        "message_key",
                        created.key(),
                        "language",
                        created.language()));
        return Response.created(URI.create("/api/v1/messages/" + created.id()))
                .entity(messageResponse(created))
                .build();
    }

    public Response updateMessage(String rawId, MessageInput input) {
        Caller caller = authorization.requireRole("admin");
        UUID id = requestParameters.parseUuid(rawId);
        Message updated =
                database.inTransaction(
                        connection -> {
                            Message row;
                            try {
                                row =
                                        queryOne(
                                                        connection,
                                                        "update messages set key = ?, language = ?, text = ?, description = ?, updated_at = ?"
                                                                + " where id = ? returning *",
                                                        params(
                                                                input.key(),
                                                                input.language(),
                                                                input.text(),
                                                                input.description(),
                                                                now(),
                                                                id),
                                                        MessageService::message)
                                                .orElseThrow(StackverseProblem::notFound);
                            } catch (RuntimeException error) {
                                if (isUniqueViolation(error)) {
                                    throw duplicateMessage(input);
                                }
                                throw error;
                            }
                            auditTrail.record(
                                    connection,
                                    caller.username(),
                                    "message.updated",
                                    "message",
                                    row.id().toString(),
                                    snapshot(row));
                            return row;
                        });
        StackverseLog.event(
                LOG,
                Logger.Level.INFO,
                "message_updated",
                "success",
                "Message updated",
                Map.of(
                        "actor",
                        caller.username(),
                        "resource_type",
                        "message",
                        "resource_id",
                        updated.id().toString(),
                        "message_key",
                        updated.key(),
                        "language",
                        updated.language()));
        return Response.ok(messageResponse(updated)).build();
    }

    public Response deleteMessage(String rawId) {
        Caller caller = authorization.requireRole("admin");
        UUID id = requestParameters.parseUuid(rawId);
        Message deleted =
                database.inTransaction(
                        connection -> {
                            Message row =
                                    queryOne(
                                                    connection,
                                                    "delete from messages where id = ? returning *",
                                                    List.of(id),
                                                    MessageService::message)
                                            .orElseThrow(StackverseProblem::notFound);
                            auditTrail.record(
                                    connection,
                                    caller.username(),
                                    "message.deleted",
                                    "message",
                                    row.id().toString(),
                                    snapshot(row));
                            return row;
                        });
        StackverseLog.event(
                LOG,
                Logger.Level.INFO,
                "message_deleted",
                "success",
                "Message deleted",
                Map.of(
                        "actor",
                        caller.username(),
                        "resource_type",
                        "message",
                        "resource_id",
                        deleted.id().toString(),
                        "message_key",
                        deleted.key(),
                        "language",
                        deleted.language()));
        return Response.noContent().build();
    }

    private static Message message(ResultSet rs) throws SQLException {
        return new Message(
                (UUID) rs.getObject("id"),
                rs.getString("key"),
                rs.getString("language"),
                rs.getString("text"),
                rs.getString("description"),
                instant(rs, "created_at"),
                instant(rs, "updated_at"));
    }

    private static MessageResponse messageResponse(Message message) {
        return new MessageResponse(
                message.id(),
                message.key(),
                message.language(),
                message.text(),
                message.description(),
                message.createdAt(),
                message.updatedAt());
    }

    private StackverseProblem duplicateMessage(MessageInput input) {
        return StackverseProblem.conflict(
                "A message with key '"
                        + input.key()
                        + "' and language '"
                        + input.language()
                        + "' already exists.");
    }

    private Map<String, Object> snapshot(Message message) {
        return detail(
                "key",
                message.key(),
                "language",
                message.language(),
                "text",
                message.text(),
                "description",
                message.description());
    }
}
