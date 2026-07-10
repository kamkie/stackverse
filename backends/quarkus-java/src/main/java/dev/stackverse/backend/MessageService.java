package dev.stackverse.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.eclipse.microprofile.jwt.JsonWebToken;
import org.jboss.logging.Logger;

@ApplicationScoped
public class MessageService extends ServiceSupport {
    @Inject
    public MessageService(
            DataSource dataSource,
            JsonWebToken jwt,
            SecurityIdentity securityIdentity,
            ObjectMapper mapper,
            Localizer localizer) {
        super(dataSource, jwt, securityIdentity, mapper, localizer);
    }

    public Response listMessages(RequestContext request) {
        int page = pagingPage(request);
        int size = pageSize(request);
        String key = singleParam(request, "key");
        String language = singleParam(request, "language");
        String q = singleParam(request, "q");
        maxLength(q, 200, "q");
        PageResponse<MessageResponse> response =
                withConnection(
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
                                        "%" + escapeLike(q) + "%",
                                        "%" + escapeLike(q) + "%");
                            }
                            long total =
                                    scalarLong(
                                            connection,
                                            "select count(*) from messages " + where.sql(),
                                            where.params());
                            List<Object> params = new ArrayList<>(where.params());
                            params.add(size);
                            params.add(offset(page, size));
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
        return etag(request, response, null);
    }

    public Response messageBundle(RequestContext request) {
        String language = localizer.resolveLanguage(request.uriInfo(), request.headers());
        MessageBundleResponse body =
                new MessageBundleResponse(language, localizer.bundle(language));
        return etag(request, body, Map.of("Content-Language", language));
    }

    public Response getMessage(RequestContext request, String rawId) {
        UUID id = parseUuid(rawId);
        MessageResponse body =
                withConnection(
                        connection ->
                                queryOne(
                                                connection,
                                                "select * from messages where id = ?",
                                                List.of(id),
                                                rs -> messageResponse(message(rs)))
                                        .orElseThrow(StackverseProblem::notFound));
        return etag(request, body, null);
    }

    public Response createMessage(MessageInput input) {
        Caller caller = requireRole("admin");
        Message created =
                inTransaction(
                        connection -> {
                            if (messageConflict(connection, input.key(), input.language(), null)) {
                                throw duplicateMessage(input);
                            }
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
                                                        ServiceSupport::message)
                                                .orElseThrow();
                            } catch (RuntimeException error) {
                                if (isUniqueViolation(error)) {
                                    throw duplicateMessage(input);
                                }
                                throw error;
                            }
                            recordAudit(
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
        Caller caller = requireRole("admin");
        UUID id = parseUuid(rawId);
        Message updated =
                inTransaction(
                        connection -> {
                            queryOne(
                                            connection,
                                            "select id from messages where id = ?",
                                            List.of(id),
                                            rs -> rs.getObject("id"))
                                    .orElseThrow(StackverseProblem::notFound);
                            if (messageConflict(connection, input.key(), input.language(), id)) {
                                throw duplicateMessage(input);
                            }
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
                                                        ServiceSupport::message)
                                                .orElseThrow();
                            } catch (RuntimeException error) {
                                if (isUniqueViolation(error)) {
                                    throw duplicateMessage(input);
                                }
                                throw error;
                            }
                            recordAudit(
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
        Caller caller = requireRole("admin");
        UUID id = parseUuid(rawId);
        Message deleted =
                inTransaction(
                        connection -> {
                            Message row =
                                    queryOne(
                                                    connection,
                                                    "delete from messages where id = ? returning *",
                                                    List.of(id),
                                                    ServiceSupport::message)
                                            .orElseThrow(StackverseProblem::notFound);
                            recordAudit(
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
}
