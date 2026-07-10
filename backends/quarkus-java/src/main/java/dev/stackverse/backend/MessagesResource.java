package dev.stackverse.backend;

import jakarta.annotation.security.RolesAllowed;
import jakarta.inject.Inject;
import jakarta.validation.Valid;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("/api/v1/messages")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class MessagesResource {
    private final MessageService service;

    @Inject
    public MessagesResource(MessageService service) {
        this.service = service;
    }

    @GET
    public Response listMessages(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return service.listMessages(new RequestContext(uriInfo, headers));
    }

    @GET
    @Path("/bundle")
    public Response messageBundle(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return service.messageBundle(new RequestContext(uriInfo, headers));
    }

    @GET
    @Path("/{id}")
    public Response getMessage(
            @Context UriInfo uriInfo, @Context HttpHeaders headers, @PathParam("id") String rawId) {
        return service.getMessage(new RequestContext(uriInfo, headers), rawId);
    }

    @POST
    @RolesAllowed("admin")
    public Response createMessage(@Valid MessageInput body) {
        return service.createMessage(body);
    }

    @PUT
    @Path("/{id}")
    @RolesAllowed("admin")
    public Response updateMessage(@PathParam("id") String rawId, @Valid MessageInput body) {
        return service.updateMessage(rawId, body);
    }

    @DELETE
    @Path("/{id}")
    @RolesAllowed("admin")
    public Response deleteMessage(@PathParam("id") String rawId) {
        return service.deleteMessage(rawId);
    }
}
