package dev.stackverse.backend;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("/api/v1/reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ReportsResource {
    private final StackverseService service;

    @Inject
    public ReportsResource(StackverseService service) {
        this.service = service;
    }

    @GET
    public Response listMyReports(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return service.listMyReports(new RequestContext(uriInfo, headers));
    }

    @PUT
    @Path("/{id}")
    public Response updateMyReport(@PathParam("id") String rawId, JsonNode body) {
        return service.updateMyReport(rawId, body);
    }

    @DELETE
    @Path("/{id}")
    public Response withdrawReport(@PathParam("id") String rawId) {
        return service.withdrawReport(rawId);
    }
}
