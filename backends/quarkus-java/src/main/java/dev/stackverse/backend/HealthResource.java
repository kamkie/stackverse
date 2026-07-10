package dev.stackverse.backend;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/")
public class HealthResource {
    private final HealthService service;

    @Inject
    public HealthResource(HealthService service) {
        this.service = service;
    }

    @GET
    @Path("/healthz")
    @Produces(MediaType.WILDCARD)
    public Response healthz() {
        return service.healthz();
    }

    @GET
    @Path("/readyz")
    @Produces(MediaType.WILDCARD)
    public Response readyz() {
        return service.readyz();
    }
}
