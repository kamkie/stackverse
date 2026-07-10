package dev.stackverse.backend;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;

@ApplicationScoped
public class HealthService {
    private final DataSource dataSource;

    HealthService(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    public Response healthz() {
        return Response.ok().build();
    }

    public Response readyz() {
        try (Connection connection = dataSource.getConnection();
                PreparedStatement statement = connection.prepareStatement("select 1")) {
            statement.executeQuery().close();
            return Response.ok().build();
        } catch (SQLException error) {
            return Response.status(Response.Status.SERVICE_UNAVAILABLE).build();
        }
    }
}
