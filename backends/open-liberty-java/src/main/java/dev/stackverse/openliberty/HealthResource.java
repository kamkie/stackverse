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

    @GET
    @Path("healthz")
    public Response healthz() {
        return Response.ok().build();
    }

    @GET
    @Path("readyz")
    public Response readyz() {
        try (Connection connection = runtime.connection();
                PreparedStatement statement = connection.prepareStatement("select 1")) {
            statement.execute();
            return Response.ok().build();
        } catch (SQLException ex) {
            return Response.status(503).build();
        }
    }
}
