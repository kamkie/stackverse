package dev.stackverse.backend;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.crypto.RSASSAVerifier;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.micronaut.context.annotation.Value;
import jakarta.inject.Singleton;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Singleton
final class JwtVerifier {
    private static final Logger LOG = LoggerFactory.getLogger(JwtVerifier.class);
    private static final Duration REFRESH_COOLDOWN = Duration.ofSeconds(30);

    private final String issuer;
    private final String configuredJwksUri;
    private final String audience;
    private final ObjectMapper mapper;
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final Object refreshLock = new Object();
    private volatile Map<String, RSAKey> keys = Map.of();
    private volatile Instant lastRefresh = Instant.EPOCH;
    private volatile String resolvedJwksUri;

    JwtVerifier(
            @Value("${stackverse.oidc.issuer-uri}") String issuer,
            @Value("${stackverse.oidc.jwks-uri:}") String configuredJwksUri,
            @Value("${stackverse.oidc.audience}") String audience,
            ObjectMapper mapper
    ) {
        this.issuer = issuer;
        this.configuredJwksUri = configuredJwksUri;
        this.audience = audience;
        this.mapper = mapper;
    }

    Identity verify(String raw) {
        try {
            SignedJWT jwt = SignedJWT.parse(raw);
            if (!List.of(JWSAlgorithm.RS256, JWSAlgorithm.RS384, JWSAlgorithm.RS512).contains(jwt.getHeader().getAlgorithm())) {
                throw new IllegalArgumentException("unsupported algorithm");
            }
            RSAKey key = key(jwt.getHeader().getKeyID());
            if (!jwt.verify(new RSASSAVerifier(key.toRSAPublicKey()))) {
                throw new IllegalArgumentException("bad signature");
            }
            JWTClaimsSet claims = jwt.getJWTClaimsSet();
            Date expires = claims.getExpirationTime();
            if (expires == null || expires.toInstant().isBefore(Instant.now())) {
                throw new IllegalArgumentException("token expired");
            }
            if (!issuer.equals(claims.getIssuer())) {
                throw new IllegalArgumentException("bad issuer");
            }
            if (claims.getAudience() == null || !claims.getAudience().contains(audience)) {
                throw new IllegalArgumentException("bad audience");
            }
            String username = claims.getStringClaim("preferred_username");
            if (username == null || username.isBlank()) {
                throw new IllegalArgumentException("missing preferred_username");
            }
            return new Identity(username, claims.getStringClaim("name"), claims.getStringClaim("email"), roles(claims));
        } catch (Exception ex) {
            throw new IllegalArgumentException("invalid token", ex);
        }
    }

    @SuppressWarnings("unchecked")
    private List<String> roles(JWTClaimsSet claims) {
        try {
            Object realm = claims.getClaim("realm_access");
            if (!(realm instanceof Map<?, ?> map)) {
                return List.of();
            }
            Object roles = map.get("roles");
            if (!(roles instanceof List<?> list)) {
                return List.of();
            }
            List<String> result = new ArrayList<>();
            for (Object role : list) {
                if (role instanceof String value) {
                    if ("moderator".equals(value) || "admin".equals(value)) {
                        result.add(value);
                    }
                }
            }
            return result;
        } catch (RuntimeException ex) {
            return List.of();
        }
    }

    private RSAKey key(String kid) throws Exception {
        if (kid == null || kid.isBlank()) {
            throw new IllegalArgumentException("missing signing key id");
        }
        RSAKey existing = keys.get(kid);
        if (existing != null) {
            return existing;
        }
        if (Duration.between(lastRefresh, Instant.now()).compareTo(REFRESH_COOLDOWN) < 0) {
            throw new IllegalArgumentException("unknown signing key");
        }
        Map<String, RSAKey> refreshed = fetchKeys();
        synchronized (refreshLock) {
            keys = Map.copyOf(refreshed);
            lastRefresh = Instant.now();
        }
        RSAKey key = keys.get(kid);
        if (key == null) {
            throw new IllegalArgumentException("unknown signing key");
        }
        return key;
    }

    private Map<String, RSAKey> fetchKeys() throws Exception {
        String uri = jwksUri();
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(uri))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("JWKS endpoint returned " + response.statusCode());
            }
            JWKSet set = JWKSet.parse(response.body());
            Map<String, RSAKey> refreshed = new HashMap<>();
            for (RSAKey key : set.getKeys().stream().filter(jwk -> jwk instanceof RSAKey).map(jwk -> (RSAKey) jwk).toList()) {
                if (key.getKeyID() != null && !key.getKeyID().isBlank()) {
                    refreshed.put(key.getKeyID(), key);
                }
            }
            if (refreshed.isEmpty()) {
                throw new IllegalStateException("JWKS contains no RSA signing keys");
            }
            return refreshed;
        } catch (Exception ex) {
            EventLog.warn(LOG, "dependency_call_failed", "failure", "Fetching JWKS failed",
                    Map.of("dependency", "keycloak", "duration_ms", (System.nanoTime() - started) / 1_000_000,
                            "error_code", "jwks_fetch_failed"));
            throw ex;
        }
    }

    private String jwksUri() throws Exception {
        if (resolvedJwksUri != null) {
            return resolvedJwksUri;
        }
        if (configuredJwksUri != null && !configuredJwksUri.isBlank()) {
            resolvedJwksUri = configuredJwksUri;
            return resolvedJwksUri;
        }
        long started = System.nanoTime();
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(issuer + "/.well-known/openid-configuration"))
                    .timeout(Duration.ofSeconds(10))
                    .GET()
                    .build();
            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IllegalStateException("OIDC discovery returned " + response.statusCode());
            }
            JsonNode root = mapper.readTree(response.body());
            String discovered = root.path("jwks_uri").asText("");
            if (discovered.isBlank()) {
                throw new IllegalStateException("OIDC discovery response has no jwks_uri");
            }
            resolvedJwksUri = discovered;
            return discovered;
        } catch (Exception ex) {
            EventLog.warn(LOG, "dependency_call_failed", "failure", "OIDC discovery failed",
                    Map.of("dependency", "keycloak", "duration_ms", (System.nanoTime() - started) / 1_000_000,
                            "error_code", "oidc_discovery_failed"));
            throw ex;
        }
    }
}
