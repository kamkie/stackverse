package dev.stackverse.backend;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/tags")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Authenticated
public class TagsResource {
    private final BookmarkService service;

    @Inject
    public TagsResource(BookmarkService service) {
        this.service = service;
    }

    @GET
    public Response listTags() {
        return service.listTags();
    }
}
