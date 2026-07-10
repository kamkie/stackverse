package dev.stackverse.backend;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/bookmarks/{id}/reports")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class BookmarkReportsResource {
    private final ReportService service;

    @Inject
    public BookmarkReportsResource(ReportService service) {
        this.service = service;
    }

    @POST
    public Response reportBookmark(@PathParam("id") String rawId, @Valid ReportInput body) {
        return service.reportBookmark(rawId, body);
    }
}
