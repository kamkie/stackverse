package dev.stackverse.backend;

import io.micronaut.http.HttpRequest;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;

@Controller("/api/v1/me")
final class MeController {
    private final SecuritySupport security;

    MeController(SecuritySupport security) {
        this.security = security;
    }

    @Get
    UserResponse get(HttpRequest<?> request) {
        Identity identity = security.require(request);
        return new UserResponse(identity.username(), identity.name(), identity.email(), identity.roles());
    }
}
