package dev.stackverse.backend;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/me")
@Produces(MediaType.APPLICATION_JSON)
public class MeResource {
    private final StackverseService service;

    @Inject
    public MeResource(StackverseService service) {
        this.service = service;
    }

    @GET
    public Response me() {
        return service.me();
    }
}
