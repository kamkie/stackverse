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
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Path("/")
@RequiresCaller
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@RequestScoped
public class ReportResource extends ResourceSupport {
    @POST
    @Path("/api/v1/bookmarks/{id}/reports")
    public Response create(@PathParam("id") String rawBookmarkId, ReportInput body) {
        Caller caller = requireCaller();
        UUID bookmarkId = uuid(rawBookmarkId);
        ReportInput input = validateDto(body);
        ApiModels.Report report =
                runtime.transaction(
                        connection -> {
                            try (PreparedStatement found =
                                    runtime.prepare(
                                            connection,
                                            "select visibility, status from bookmarks where id = ? for update",
                                            bookmarkId)) {
                                try (ResultSet rs = found.executeQuery()) {
                                    if (!rs.next()
                                            || !"public".equals(rs.getString("visibility"))
                                            || !"active".equals(rs.getString("status"))) {
                                        throw ApiProblem.notFound();
                                    }
                                }
                            }
                            if (exists(
                                    connection,
                                    "select 1 from reports where bookmark_id = ? and reporter = ? and status = 'open'",
                                    bookmarkId,
                                    caller.username())) {
                                throw ApiProblem.conflict(
                                        "You already have an open report on this bookmark.");
                            }
                            UUID id = UUID.randomUUID();
                            try (PreparedStatement statement =
                                    runtime.prepare(
                                            connection,
                                            """
          insert into reports (id, bookmark_id, reporter, reason, comment, status, created_at)
          values (?, ?, ?, ?, ?, 'open', ?)
          returning *
          """,
                                            id,
                                            bookmarkId,
                                            caller.username(),
                                            input.reason(),
                                            input.comment(),
                                            Instant.now())) {
                                try (ResultSet rs = statement.executeQuery()) {
                                    rs.next();
                                    return report(rs);
                                }
                            } catch (SQLException ex) {
                                if ("23505".equals(ex.getSQLState()))
                                    throw ApiProblem.conflict(
                                            "You already have an open report on this bookmark.");
                                throw ex;
                            }
                        });
        log.event(
                "info",
                "report_created",
                "success",
                "Report created on a public bookmark",
                Map.of(
                        "actor",
                        caller.username(),
                        "resource_type",
                        "report",
                        "resource_id",
                        report.id()));
        return Response.status(Response.Status.CREATED)
                .type(MediaType.APPLICATION_JSON_TYPE.withCharset("utf-8"))
                .entity(JsonSupport.jsonString(report))
                .build();
    }

    @GET
    @Path("/api/v1/reports")
    public Response listMine() throws SQLException {
        Caller caller = requireCaller();
        Paging paging = paging();
        String status = reportStatus(single("status"), false);
        List<Object> params = new ArrayList<>(List.of(caller.username()));
        String where = "reporter = ?";
        if (status != null) {
            where += " and status = ?";
            params.add(status);
        }
        ResponsePage result =
                reportPage(where, params, paging, "order by created_at desc, id desc");
        return JsonSupport.json(page(result.items(), paging, result.total()));
    }

    @PUT
    @Path("/api/v1/reports/{id}")
    public Response updateMine(@PathParam("id") String rawId, ReportInput body) {
        Caller caller = requireCaller();
        UUID id = uuid(rawId);
        ApiModels.Report updated =
                runtime.transaction(
                        connection -> {
                            ApiModels.Report report = ownReport(connection, caller.username(), id);
                            if (!"open".equals(report.status()))
                                throw ApiProblem.conflict("The report has already been resolved.");
                            ReportInput input = validateDto(body);
                            try (PreparedStatement statement =
                                    runtime.prepare(
                                            connection,
                                            "update reports set reason = ?, comment = ? where id = ? returning *",
                                            input.reason(),
                                            input.comment(),
                                            id)) {
                                try (ResultSet rs = statement.executeQuery()) {
                                    rs.next();
                                    return report(rs);
                                }
                            }
                        });
        log.event(
                "info",
                "report_updated",
                "success",
                "Report updated by its reporter",
                Map.of(
                        "actor",
                        caller.username(),
                        "resource_type",
                        "report",
                        "resource_id",
                        id.toString()));
        return JsonSupport.json(updated);
    }

    @DELETE
    @Path("/api/v1/reports/{id}")
    public Response withdraw(@PathParam("id") String rawId) {
        Caller caller = requireCaller();
        UUID id = uuid(rawId);
        runtime.transaction(
                connection -> {
                    ApiModels.Report report = ownReport(connection, caller.username(), id);
                    if (!"open".equals(report.status()))
                        throw ApiProblem.conflict("The report has already been resolved.");
                    try (PreparedStatement statement =
                            runtime.prepare(connection, "delete from reports where id = ?", id)) {
                        statement.executeUpdate();
                    }
                    return null;
                });
        log.event(
                "info",
                "report_withdrawn",
                "success",
                "Report withdrawn by its reporter",
                Map.of(
                        "actor",
                        caller.username(),
                        "resource_type",
                        "report",
                        "resource_id",
                        id.toString()));
        return Response.noContent().build();
    }
}
