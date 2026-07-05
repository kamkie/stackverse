package dev.stackverse.backend;

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
public class TagsResource {
    private final StackverseService service;

    @Inject
    public TagsResource(StackverseService service) {
        this.service = service;
    }

    @GET
    public Response listTags() {
        return service.listTags();
    }
}
