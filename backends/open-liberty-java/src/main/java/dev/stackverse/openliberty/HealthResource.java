package dev.stackverse.openliberty;

import jakarta.enterprise.context.RequestScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;

@Path("/")
@RequestScoped
public class HealthResource {
    @Inject JdbcRepository runtime;
    @Inject EventLogger log;

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
            return true;
        } catch (SQLException | RuntimeException ex) {
            log.dependencyFailure("postgres", ex, EventLogger.elapsedMillis(startedAt));
            return false;
        }
    }
}
