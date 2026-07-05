package dev.stackverse.openliberty;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.LinkedHashMap;
import java.util.Map;

@Path("/api/v1/me")
@Produces(MediaType.APPLICATION_JSON)
public class AccountResource extends ResourceSupport {
  @GET
  public Response me() {
    Caller caller = requireCaller();
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("username", caller.username());
    if (caller.name() != null) body.put("name", caller.name());
    if (caller.email() != null) body.put("email", caller.email());
    body.put("roles", caller.roles().stream()
        .filter(role -> role.equals("admin") || role.equals("moderator"))
        .sorted()
        .toList());
    return JsonSupport.json(body);
  }
}
