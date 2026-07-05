package dev.stackverse.backend;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.inject.Inject;
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

@Path("/api/v1/bookmarks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BookmarksResource {
    private final StackverseService service;

    @Inject
    public BookmarksResource(StackverseService service) {
        this.service = service;
    }

    @GET
    public Response listBookmarksV1(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return service.listBookmarksV1(new RequestContext(uriInfo, headers));
    }

    @POST
    public Response createBookmark(JsonNode body) {
        return service.createBookmark(body);
    }

    @GET
    @Path("/{id}")
    public Response getBookmark(@PathParam("id") String rawId) {
        return service.getBookmark(rawId);
    }

    @PUT
    @Path("/{id}")
    public Response updateBookmark(@PathParam("id") String rawId, JsonNode body) {
        return service.updateBookmark(rawId, body);
    }

    @DELETE
    @Path("/{id}")
    public Response deleteBookmark(@PathParam("id") String rawId) {
        return service.deleteBookmark(rawId);
    }
}
