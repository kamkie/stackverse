package dev.stackverse.openliberty;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/api/v1/messages")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequestScoped
public class MessageResource extends ResourceSupport {
    @GET
    public Response list() throws SQLException {
        Paging paging = paging();
        String key = single("key");
        String language = single("language");
        String q = single("q");
        requireMax(q, 200, "q");
        List<String> conditions = new ArrayList<>(List.of("true"));
        List<Object> params = new ArrayList<>();
        if (key != null) {
            conditions.add("key = ?");
            params.add(key);
        }
        if (language != null) {
            conditions.add("language = ?");
            params.add(language);
        }
        if (q != null && !q.isBlank()) {
            conditions.add("(key ilike ? escape '\\' or text ilike ? escape '\\')");
            String pattern = "%" + escapeLike(q) + "%";
            params.add(pattern);
            params.add(pattern);
        }
        ResponsePage result =
                queryPage(
                        "messages",
                        String.join(" and ", conditions),
                        "order by key, language",
                        params,
                        paging,
                        this::message);
        return JsonSupport.etagResponse(
                headers.getHeaderString("If-None-Match"),
                page(result.items(), paging, result.total()));
    }

    @GET
    @Path("bundle")
    public Response bundle() throws SQLException {
        String language =
                messages.resolveLanguage(
                        messages.firstParam(uriInfo.getQueryParameters().get("lang")),
                        headers.getHeaderString("Accept-Language"));
        Map<String, String> bundle = messages.bundle(language);
        Response response =
                JsonSupport.etagResponse(
                        headers.getHeaderString("If-None-Match"),
                        new ApiModels.MessageBundle(language, bundle));
        return Response.fromResponse(response).header("Content-Language", language).build();
    }

    @GET
    @Path("{id}")
    public Response get(@PathParam("id") String rawId) throws SQLException {
        UUID id = uuid(rawId);
        ApiModels.Message found = null;
        try (Connection connection = runtime.connection();
                PreparedStatement statement =
                        runtime.prepare(connection, "select * from messages where id = ?", id)) {
            try (ResultSet rs = statement.executeQuery()) {
                if (rs.next()) found = message(rs);
            }
        }
        if (found == null) throw ApiProblem.notFound();
        return JsonSupport.etagResponse(headers.getHeaderString("If-None-Match"), found);
    }

    @POST
    @RequiresRole("admin")
    public Response create(MessageInput body) {
        Caller caller = requireRole("admin");
        MessageInput input = messageInput(body);
        UUID id = UUID.randomUUID();
        ApiModels.Message created =
                runtime.transaction(
                        connection -> {
                            try (PreparedStatement statement =
                                    runtime.prepare(
                                            connection,
                                            """
          insert into messages (id, key, language, text, description, created_at, updated_at)
          values (?, ?, ?, ?, ?, ?, ?)
          returning *
          """,
                                            id,
                                            input.key(),
                                            input.language(),
                                            input.text(),
                                            input.description(),
                                            Instant.now(),
                                            Instant.now())) {
                                try (ResultSet rs = statement.executeQuery()) {
                                    rs.next();
                                    ApiModels.Message row = message(rs);
                                    audit(
                                            connection,
                                            caller.username(),
                                            "message.created",
                                            "message",
                                            id.toString(),
                                            input.toMap());
                                    return row;
                                }
                            } catch (SQLException ex) {
                                if ("23505".equals(ex.getSQLState()))
                                    throw ApiProblem.conflict(
                                            "A message with this key and language already exists.");
                                throw ex;
                            }
                        });
        log.event(
                "info",
                "message_created",
                "success",
                "Message created",
                Map.of(
                        "actor",
                        caller.username(),
                        "resource_type",
                        "message",
                        "resource_id",
                        id.toString()));
        return JsonSupport.created("/api/v1/messages/" + id, created);
    }

    @PUT
    @Path("{id}")
    @RequiresRole("admin")
    public Response update(@PathParam("id") String rawId, MessageInput body) {
        Caller caller = requireRole("admin");
        UUID id = uuid(rawId);
        MessageInput input = messageInput(body);
        ApiModels.Message updated =
                runtime.transaction(
                        connection -> {
                            if (!exists(connection, "select 1 from messages where id = ?", id))
                                throw ApiProblem.notFound();
                            try (PreparedStatement statement =
                                    runtime.prepare(
                                            connection,
                                            """
          update messages
          set key = ?, language = ?, text = ?, description = ?, updated_at = ?
          where id = ?
          returning *
          """,
                                            input.key(),
                                            input.language(),
                                            input.text(),
                                            input.description(),
                                            Instant.now(),
                                            id)) {
                                try (ResultSet rs = statement.executeQuery()) {
                                    rs.next();
                                    ApiModels.Message row = message(rs);
                                    audit(
                                            connection,
                                            caller.username(),
                                            "message.updated",
                                            "message",
                                            id.toString(),
                                            input.toMap());
                                    return row;
                                }
                            } catch (SQLException ex) {
                                if ("23505".equals(ex.getSQLState()))
                                    throw ApiProblem.conflict(
                                            "A message with this key and language already exists.");
                                throw ex;
                            }
                        });
        log.event(
                "info",
                "message_updated",
                "success",
                "Message updated",
                Map.of(
                        "actor",
                        caller.username(),
                        "resource_type",
                        "message",
                        "resource_id",
                        id.toString()));
        return JsonSupport.json(updated);
    }

    @DELETE
    @Path("{id}")
    @RequiresRole("admin")
    public Response delete(@PathParam("id") String rawId) {
        Caller caller = requireRole("admin");
        UUID id = uuid(rawId);
        runtime.transaction(
                connection -> {
                    ApiModels.Message deleted = null;
                    try (PreparedStatement statement =
                            runtime.prepare(
                                    connection,
                                    "delete from messages where id = ? returning *",
                                    id)) {
                        try (ResultSet rs = statement.executeQuery()) {
                            if (rs.next()) deleted = message(rs);
                        }
                    }
                    if (deleted == null) throw ApiProblem.notFound();
                    audit(
                            connection,
                            caller.username(),
                            "message.deleted",
                            "message",
                            id.toString(),
                            deleted);
                    return null;
                });
        log.event(
                "info",
                "message_deleted",
                "success",
                "Message deleted",
                Map.of(
                        "actor",
                        caller.username(),
                        "resource_type",
                        "message",
                        "resource_id",
                        id.toString()));
        return Response.noContent().build();
    }
}
