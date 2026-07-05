package dev.stackverse.backend;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
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
public class BookmarkReportsResource {
    private final StackverseService service;

    @Inject
    public BookmarkReportsResource(StackverseService service) {
        this.service = service;
    }

    @POST
    public Response reportBookmark(@PathParam("id") String rawId, JsonNode body) {
        return service.reportBookmark(rawId, body);
    }
}
