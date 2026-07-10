package dev.stackverse.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import javax.sql.DataSource;
import org.eclipse.microprofile.jwt.JsonWebToken;

@ApplicationScoped
public class HealthService extends ServiceSupport {
    @Inject
    public HealthService(
            DataSource dataSource,
            JsonWebToken jwt,
            SecurityIdentity securityIdentity,
            ObjectMapper mapper,
            Localizer localizer) {
        super(dataSource, jwt, securityIdentity, mapper, localizer);
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
