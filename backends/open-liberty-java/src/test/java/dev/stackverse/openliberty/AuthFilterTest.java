package dev.stackverse.openliberty;

import static org.junit.jupiter.api.Assertions.assertEquals;

import jakarta.json.Json;
import java.util.List;
import java.util.Map;
import org.eclipse.microprofile.auth.LoginConfig;
import org.junit.jupiter.api.Test;

class AuthFilterTest {
    @Test
    void mapsAndSortsKeycloakRealmRolesAfterLibertyValidation() {
        assertEquals(
                List.of("admin", "moderator"),
                AuthFilter.realmRoles(Map.of("roles", List.of("moderator", "admin"))));

        assertEquals(
                List.of("admin", "moderator"),
                AuthFilter.realmRoles(
                        Json.createObjectBuilder()
                                .add(
                                        "roles",
                                        Json.createArrayBuilder().add("moderator").add("admin"))
                                .build()));
    }

    @Test
    void applicationSelectsMicroProfileJwtAuthentication() {
        LoginConfig login = StackverseApplication.class.getAnnotation(LoginConfig.class);
        assertEquals("MP-JWT", login.authMethod());
    }

    @Test
    void privilegedEndpointsDeclareTheirRealmRole() throws NoSuchMethodException {
        assertEquals(
                "admin",
                MessageResource.class
                        .getMethod("create", MessageInput.class)
                        .getAnnotation(RequiresRole.class)
                        .value());
        assertEquals(
                "moderator",
                ModerationResource.class
                        .getMethod("reports")
                        .getAnnotation(RequiresRole.class)
                        .value());
    }
}
