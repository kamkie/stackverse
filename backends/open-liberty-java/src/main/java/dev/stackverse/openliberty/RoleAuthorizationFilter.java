package dev.stackverse.openliberty;

import jakarta.annotation.Priority;
import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

/** Enforces {@link RequiresRole} after Liberty has authenticated the MP JWT. */
@Provider
@RequiresRole("")
@Priority(Priorities.AUTHORIZATION)
@RequestScoped
public class RoleAuthorizationFilter implements ContainerRequestFilter {
    @Inject EventLogger log;
    @Context ResourceInfo resourceInfo;
    @Context HttpServletRequest request;

    @Override
    public void filter(ContainerRequestContext context) {
        RequiresRole required = resourceInfo.getResourceMethod().getAnnotation(RequiresRole.class);
        if (required == null) {
            required = resourceInfo.getResourceClass().getAnnotation(RequiresRole.class);
        }
        Caller caller = (Caller) request.getAttribute(AuthFilter.CALLER_ATTRIBUTE);
        if (caller == null) {
            context.abortWith(
                    JsonSupport.problem(
                            401, "Unauthorized", "Missing or invalid bearer token.", null));
        } else if (!caller.hasRole(required.value())) {
            log.event(
                    "info",
                    "authz_denied",
                    "denied",
                    "Denied a request lacking the required role",
                    java.util.Map.of("actor", caller.username()));
            context.abortWith(
                    JsonSupport.problem(
                            403,
                            "Forbidden",
                            "You do not have the role required for this operation.",
                            null));
        }
    }
}
