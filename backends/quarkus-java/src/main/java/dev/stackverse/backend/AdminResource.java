package dev.stackverse.backend;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
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

@Path("/api/v1/admin")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class AdminResource {
    private final StackverseService service;

    @Inject
    public AdminResource(StackverseService service) {
        this.service = service;
    }

    @GET
    @Path("/reports")
    public Response listReportQueue(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return service.listReportQueue(new RequestContext(uriInfo, headers));
    }

    @PUT
    @Path("/reports/{id}")
    public Response resolveReport(@PathParam("id") String rawId, JsonNode body) {
        return service.resolveReport(rawId, body);
    }

    @PUT
    @Path("/bookmarks/{id}/status")
    public Response setBookmarkStatus(@PathParam("id") String rawId, JsonNode body) {
        return service.setBookmarkStatus(rawId, body);
    }

    @GET
    @Path("/users")
    public Response listUsers(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return service.listUsers(new RequestContext(uriInfo, headers));
    }

    @GET
    @Path("/users/{username}")
    public Response getUser(@PathParam("username") String username) {
        return service.getUser(username);
    }

    @PUT
    @Path("/users/{username}/status")
    public Response setUserStatus(@PathParam("username") String username, JsonNode body) {
        return service.setUserStatus(username, body);
    }

    @GET
    @Path("/audit-log")
    public Response auditLog(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return service.auditLog(new RequestContext(uriInfo, headers));
    }

    @GET
    @Path("/stats")
    public Response stats(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return service.stats(new RequestContext(uriInfo, headers));
    }
}
