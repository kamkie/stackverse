package dev.stackverse.openliberty;

import jakarta.ws.rs.NameBinding;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Declarative Stackverse realm-role guard.
 *
 * <p>Keycloak deliberately exposes realm roles in {@code realm_access.roles}; this annotation
 * adapts that existing token shape after Open Liberty has performed MicroProfile JWT validation.
 */
@NameBinding
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE, ElementType.METHOD})
public @interface RequiresRole {
    String value();
}
