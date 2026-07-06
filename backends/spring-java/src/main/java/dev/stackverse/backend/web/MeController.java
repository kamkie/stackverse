package dev.stackverse.backend.web;

import java.util.Set;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class MeController {
    private static final Set<String> APP_ROLES = Set.of("moderator", "admin");

    @GetMapping("/api/v1/me")
    public UserResponse me(JwtAuthenticationToken authentication) {
        return new UserResponse(
            authentication.getName(),
            authentication.getToken().getClaimAsString("name"),
            authentication.getToken().getClaimAsString("email"),
            authentication.getAuthorities().stream()
                .map(authority -> authority.getAuthority().replaceFirst("^ROLE_", ""))
                .filter(APP_ROLES::contains)
                .sorted()
                .toList()
        );
    }
}
