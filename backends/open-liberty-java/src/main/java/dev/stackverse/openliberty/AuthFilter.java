package dev.stackverse.openliberty;

import com.fasterxml.jackson.databind.JsonNode;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.proc.JWSKeySelector;
import com.nimbusds.jose.proc.JWSVerificationKeySelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.jwk.source.RemoteJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.proc.ConfigurableJWTProcessor;
import com.nimbusds.jwt.proc.DefaultJWTProcessor;
import jakarta.annotation.Priority;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;
import java.net.URI;
import java.net.URL;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

@Provider
@Priority(Priorities.AUTHENTICATION)
public class AuthFilter implements ContainerRequestFilter {
  static final String CALLER_ATTRIBUTE = "stackverse.caller";
  private static final String AUDIENCE = "stackverse-api";
  private static volatile ConfigurableJWTProcessor<SecurityContext> jwtProcessor;

  @Context
  HttpServletRequest servletRequest;

  @Override
  public void filter(ContainerRequestContext request) {
    RuntimeSupport.boot();
    servletRequest.removeAttribute(CALLER_ATTRIBUTE);
    String header = request.getHeaderString("Authorization");
    if (header == null || !header.startsWith("Bearer ")) {
      return;
    }
    Caller caller;
    try {
      caller = verify(header.substring("Bearer ".length()));
    } catch (Exception ex) {
      Log.event("info", "jwt_validation_failed", "failure", "Rejected a bearer token",
          Map.of("error_code", "invalid_token"));
      request.abortWith(JsonSupport.problem(401, "Unauthorized", "Missing or invalid bearer token.", null));
      return;
    }
    AccountState state = recordSeen(caller.username());
    if ("blocked".equals(state.status())) {
      Log.event("warn", "blocked_user_rejected", "denied", "Refused a request from a blocked account",
          Map.of("actor", caller.username()));
      String language = StackverseResource.resolveLanguage(
          StackverseResource.firstParam(request.getUriInfo().getQueryParameters().get("lang")),
          request.getHeaderString("Accept-Language"));
      request.abortWith(JsonSupport.problem(403, "Forbidden",
          StackverseResource.localize("error.account.blocked", language), null));
      return;
    }
    servletRequest.setAttribute(CALLER_ATTRIBUTE, caller);
  }

  private static Caller verify(String token) throws Exception {
    JWTClaimsSet claims = processor().process(token, null);
    Instant now = Instant.now();
    if (!RuntimeSupport.CONFIG.issuerUri().equals(claims.getIssuer())) {
      throw new IllegalArgumentException("invalid issuer");
    }
    if (claims.getAudience() == null || !claims.getAudience().contains(AUDIENCE)) {
      throw new IllegalArgumentException("invalid audience");
    }
    if (claims.getExpirationTime() == null || claims.getExpirationTime().toInstant().isBefore(now)) {
      throw new IllegalArgumentException("expired token");
    }
    if (claims.getNotBeforeTime() != null && claims.getNotBeforeTime().toInstant().isAfter(now.plusSeconds(5))) {
      throw new IllegalArgumentException("token not yet valid");
    }
    String username = claims.getStringClaim("preferred_username");
    if (username == null || username.isBlank()) {
      throw new IllegalArgumentException("missing preferred_username");
    }
    List<String> roles = new ArrayList<>();
    Object realmAccess = claims.getClaim("realm_access");
    if (realmAccess instanceof Map<?, ?> map && map.get("roles") instanceof List<?> rawRoles) {
      for (Object role : rawRoles) {
        if (role instanceof String text) {
          roles.add(text);
        }
      }
    }
    roles.sort(Comparator.naturalOrder());
    return new Caller(username, roles, claims.getStringClaim("name"), claims.getStringClaim("email"));
  }

  private static ConfigurableJWTProcessor<SecurityContext> processor() throws Exception {
    ConfigurableJWTProcessor<SecurityContext> local = jwtProcessor;
    if (local != null) {
      return local;
    }
    synchronized (AuthFilter.class) {
      if (jwtProcessor != null) {
        return jwtProcessor;
      }
      URL jwksUrl = jwksUrl();
      JWKSource<SecurityContext> source = new RemoteJWKSet<>(jwksUrl.toURI().toURL());
      JWSKeySelector<SecurityContext> selector = new JWSVerificationKeySelector<>(JWSAlgorithm.RS256, source);
      DefaultJWTProcessor<SecurityContext> processor = new DefaultJWTProcessor<>();
      processor.setJWSKeySelector(selector);
      jwtProcessor = processor;
      return processor;
    }
  }

  private static URL jwksUrl() throws Exception {
    String configured = RuntimeSupport.CONFIG.jwksUri();
    if (configured != null) {
      return URI.create(configured).toURL();
    }
    Instant started = Instant.now();
    try {
      JsonNode discovery = JsonSupport.MAPPER.readTree(
          URI.create(RuntimeSupport.CONFIG.issuerUri() + "/.well-known/openid-configuration").toURL());
      return URI.create(discovery.get("jwks_uri").asText()).toURL();
    } catch (Exception ex) {
      Log.event("error", "dependency_call_failed", "failure", "OIDC discovery failed",
          Map.of("dependency", "keycloak", "duration_ms", java.time.Duration.between(started, Instant.now()).toMillis(),
              "error_code", "oidc_discovery_failed"));
      throw ex;
    }
  }

  private static AccountState recordSeen(String username) {
    try (Connection connection = RuntimeSupport.connection();
         PreparedStatement statement = RuntimeSupport.prepare(connection,
             """
             insert into user_accounts (username, first_seen, last_seen, status)
             values (?, ?, ?, 'active')
             on conflict (username) do update set last_seen = excluded.last_seen
             returning status
             """,
             username, Instant.now(), Instant.now())) {
      try (var rs = statement.executeQuery()) {
        rs.next();
        return new AccountState(rs.getString("status"));
      }
    } catch (Exception ex) {
      throw new RuntimeException(ex);
    }
  }
}

record Caller(String username, List<String> roles, String name, String email) {
  boolean hasRole(String role) {
    return roles.contains(role);
  }
}

record AccountState(String status) {}
