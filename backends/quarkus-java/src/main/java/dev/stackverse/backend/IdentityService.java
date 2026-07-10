package dev.stackverse.backend;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.core.Response;

@ApplicationScoped
public class IdentityService {
    private final Authorization authorization;

    IdentityService(Authorization authorization) {
        this.authorization = authorization;
    }

    public Response me() {
        Caller caller = authorization.requireCaller();
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
