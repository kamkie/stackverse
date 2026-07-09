package dev.stackverse.openliberty;

import jakarta.enterprise.context.RequestScoped;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/api/v1/me")
@Produces(MediaType.APPLICATION_JSON)
@RequestScoped
public class AccountResource extends ResourceSupport {
    @GET
    public Response me() {
        Caller caller = requireCaller();
        return JsonSupport.json(
                new ApiModels.User(
                        caller.username(),
                        caller.name(),
                        caller.email(),
                        caller.roles().stream()
                                .filter(role -> role.equals("admin") || role.equals("moderator"))
                                .sorted()
                                .toList()));
    }
}
