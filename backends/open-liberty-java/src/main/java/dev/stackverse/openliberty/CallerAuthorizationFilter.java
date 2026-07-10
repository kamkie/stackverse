package dev.stackverse.openliberty;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.RequestScoped;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;

/** Rejects anonymous protected requests before JAX-RS reads and validates their entity body. */
@Provider
@RequiresCaller
@Priority(Priorities.AUTHORIZATION)
@RequestScoped
public class CallerAuthorizationFilter implements ContainerRequestFilter {
    @Context HttpServletRequest request;

    @Override
    public void filter(ContainerRequestContext context) {
        if (request.getAttribute(AuthFilter.CALLER_ATTRIBUTE) != null) {
            return;
        }
        Response response =
                JsonSupport.problem(401, "Unauthorized", "Missing or invalid bearer token.", null);
        if (DeprecationHeaders.isDeprecatedV1Bookmarks(
                context.getMethod(), context.getUriInfo().getPath())) {
            response = DeprecationHeaders.addV1BookmarkHeaders(response);
        }
        context.abortWith(response);
    }
}
