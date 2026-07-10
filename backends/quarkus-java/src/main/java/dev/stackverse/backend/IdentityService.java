package dev.stackverse.backend;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.security.identity.SecurityIdentity;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.Response;
import javax.sql.DataSource;
import org.eclipse.microprofile.jwt.JsonWebToken;

@ApplicationScoped
public class IdentityService extends ServiceSupport {
    @Inject
    public IdentityService(
            DataSource dataSource,
            JsonWebToken jwt,
            SecurityIdentity securityIdentity,
            ObjectMapper mapper,
            Localizer localizer) {
        super(dataSource, jwt, securityIdentity, mapper, localizer);
    }

    public Response me() {
        Caller caller = requireCaller();
        return Response.ok(
                        new MeResponse(
                                caller.username(),
                                caller.name(),
                                caller.email(),
                                caller.roles().stream()
                                        .filter(
                                                role ->
                                                        role.equals("moderator")
                                                                || role.equals("admin"))
                                        .sorted()
                                        .toList()))
                .build();
    }
}
