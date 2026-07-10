package dev.stackverse.backend;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
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
    private final AdminService service;
    private final ReportService reports;

    @Inject
    public AdminResource(AdminService service, ReportService reports) {
        this.service = service;
        this.reports = reports;
    }

    @GET
    @Path("/reports")
    @RolesAllowed("moderator")
    public Response listReportQueue(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return reports.listReportQueue(new RequestContext(uriInfo, headers));
    }

    @PUT
    @Path("/reports/{id}")
    @RolesAllowed("moderator")
    public Response resolveReport(@PathParam("id") String rawId, @Valid ResolutionInput body) {
        return reports.resolveReport(rawId, body);
    }

    @PUT
    @Path("/bookmarks/{id}/status")
    @RolesAllowed("moderator")
    public Response setBookmarkStatus(
            @PathParam("id") String rawId, @Valid BookmarkStatusInput body) {
        return reports.setBookmarkStatus(rawId, body);
    }

    @GET
    @Path("/users")
    @RolesAllowed("admin")
    public Response listUsers(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return service.listUsers(new RequestContext(uriInfo, headers));
    }

    @GET
    @Path("/users/{username}")
    @RolesAllowed("admin")
    public Response getUser(@PathParam("username") String username) {
        return service.getUser(username);
    }

    @PUT
    @Path("/users/{username}/status")
    @RolesAllowed("admin")
    public Response setUserStatus(
            @PathParam("username") String username, @Valid UserStatusInput body) {
        return service.setUserStatus(username, body);
    }

    @GET
    @Path("/audit-log")
    @RolesAllowed("admin")
    public Response auditLog(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return service.auditLog(new RequestContext(uriInfo, headers));
    }

    @GET
    @Path("/stats")
    @RolesAllowed("moderator")
    public Response stats(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return service.stats(new RequestContext(uriInfo, headers));
    }
}
