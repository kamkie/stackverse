package dev.stackverse.backend;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.HttpHeaders;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("/api/v2/bookmarks")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class BookmarksV2Resource {
    private final StackverseService service;

    @Inject
    public BookmarksV2Resource(StackverseService service) {
        this.service = service;
    }

    @GET
    public Response listBookmarksV2(@Context UriInfo uriInfo, @Context HttpHeaders headers) {
        return service.listBookmarksV2(new RequestContext(uriInfo, headers));
    }
}
