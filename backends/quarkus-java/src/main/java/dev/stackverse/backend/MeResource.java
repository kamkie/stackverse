package dev.stackverse.backend;

import io.quarkus.security.Authenticated;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/me")
@Produces(MediaType.APPLICATION_JSON)
@Authenticated
public class MeResource {
    private final IdentityService service;

    @Inject
    public MeResource(IdentityService service) {
        this.service = service;
    }

    @GET
    public Response me() {
        return service.me();
    }
}
