package dev.stackverse.openliberty;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

@Path("/")
@ApplicationScoped
public class HealthResource {
    @Inject RuntimeSupport runtime;
    @Inject EventLogger log;

    private final AtomicBoolean wasReady = new AtomicBoolean(true);

    @GET
    @Path("healthz")
    public Response healthz() {
        return Response.ok().build();
    }

    @GET
    @Path("readyz")
    public Response readyz() {
        return databaseReady() ? Response.ok().build() : Response.status(503).build();
    }

    boolean databaseReady() {
        long startedAt = System.nanoTime();
        try (Connection connection = runtime.connection();
                PreparedStatement statement = connection.prepareStatement("select 1")) {
            statement.execute();
            if (wasReady.compareAndSet(false, true)) {
                log.event(
                        "info",
                        "dependency_recovered",
                        "success",
                        "Readiness restored: database reachable again",
                        Map.of("dependency", "postgres"));
            }
            return true;
        } catch (SQLException | RuntimeException ex) {
            if (wasReady.compareAndSet(true, false)) {
                log.event(
                        "warn",
                        "dependency_call_failed",
                        "failure",
                        "Readiness lost: database unreachable",
                        Map.of(
                                "dependency", "postgres",
                                "duration_ms", EventLogger.elapsedMillis(startedAt),
                                "error_code", EventLogger.errorCode(ex)));
            }
            return false;
        }
    }
}
