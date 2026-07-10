package dev.stackverse.openliberty;

import jakarta.annotation.Priority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

/** Captures request start time so terminal dependency events include useful latency. */
@Provider
@Priority(Priorities.AUTHENTICATION - 100)
public class RequestTimingFilter implements ContainerRequestFilter {
    static final String STARTED_AT_ATTRIBUTE = "stackverse.request.started-at";

    @Context HttpServletRequest request;

    @Override
    public void filter(ContainerRequestContext requestContext) {
        request.setAttribute(STARTED_AT_ATTRIBUTE, System.nanoTime());
    }

    static long elapsedMillis(HttpServletRequest request) {
        Object startedAt = request.getAttribute(STARTED_AT_ATTRIBUTE);
        if (startedAt instanceof Long nanos) {
            return EventLogger.elapsedMillis(nanos);
        }
        return 0L;
    }
}
