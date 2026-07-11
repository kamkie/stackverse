package dev.stackverse.backend;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

final class JwtVerifierTest {
    private static final String ISSUER = "https://idp.example.test/realms/stackverse";
    private static final String AUDIENCE = "stackverse-api";

    private RSAKey signingKey;

    @BeforeEach
    void generateSigningKey() throws Exception {
        signingKey = new RSAKeyGenerator(2048).keyID("stackverse-key").generate();
    }

    @Test
    void verifiesSignatureClaimsAndOnlyApplicationRoles() throws Exception {
        JwtVerifier verifier = verifier("http://unused.example.test/jwks");
        cache(verifier, signingKey.toPublicJWK());
        JWTClaimsSet claims = claims(
                ISSUER, AUDIENCE, Date.from(Instant.now().plusSeconds(60)), "alice",
                Map.of("roles", List.of("default-roles-stackverse", "moderator", 42, "admin")));

        Identity identity = verifier.verify(sign(signingKey, signingKey.getKeyID(), claims));

        assertThat(identity.username()).isEqualTo("alice");
        assertThat(identity.name()).isEqualTo("Alice Example");
        assertThat(identity.email()).isEqualTo("alice@example.test");
        assertThat(identity.roles()).containsExactly("moderator", "admin");
    }

    @Test
    void rejectsExpiredWrongIssuerWrongAudienceMissingUsernameAndBadSignature() throws Exception {
        JwtVerifier verifier = verifier("http://unused.example.test/jwks");
        cache(verifier, signingKey.toPublicJWK());

        assertInvalid(verifier, sign(signingKey, signingKey.getKeyID(),
                claims(ISSUER, AUDIENCE, Date.from(Instant.now().minusSeconds(1)), "alice", Map.of())));
        assertInvalid(verifier, sign(signingKey, signingKey.getKeyID(),
                claims("https://wrong.example.test", AUDIENCE, future(), "alice", Map.of())));
        assertInvalid(verifier, sign(signingKey, signingKey.getKeyID(),
                claims(ISSUER, "wrong-api", future(), "alice", Map.of())));
        assertInvalid(verifier, sign(signingKey, signingKey.getKeyID(),
                claims(ISSUER, AUDIENCE, future(), null, Map.of())));

        RSAKey otherKey = new RSAKeyGenerator(2048).keyID(signingKey.getKeyID()).generate();
        assertInvalid(verifier, sign(otherKey, signingKey.getKeyID(),
                claims(ISSUER, AUDIENCE, future(), "alice", Map.of())));
    }

    @Test
    void rejectsUnsupportedAlgorithmsBeforeAnyKeyLookup() throws Exception {
        JWTClaimsSet claims = claims(ISSUER, AUDIENCE, future(), "alice", Map.of());
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.HS256).keyID("symmetric").build(), claims);
        token.sign(new MACSigner("01234567890123456789012345678901"));

        assertInvalid(verifier("http://127.0.0.1:1/never-called"), token.serialize());
    }

    @Test
    void discoversJwksVerifiesTokenAndCachesTheResolvedKey() throws Exception {
        AtomicInteger discoveryCalls = new AtomicInteger();
        AtomicInteger jwksCalls = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        server.createContext("/.well-known/openid-configuration", exchange -> {
            discoveryCalls.incrementAndGet();
            respond(exchange, 200, "{\"jwks_uri\":\"" + base + "/keys\"}");
        });
        server.createContext("/keys", exchange -> {
            jwksCalls.incrementAndGet();
            respond(exchange, 200, new JWKSet(signingKey.toPublicJWK()).toString());
        });
        server.start();
        try {
            JwtVerifier verifier = new JwtVerifier(base, "", AUDIENCE, new ObjectMapper());
            String token = sign(signingKey, signingKey.getKeyID(),
                    claims(base, AUDIENCE, future(), "alice", Map.of()));

            assertThat(verifier.verify(token).username()).isEqualTo("alice");
            assertThat(verifier.verify(token).username()).isEqualTo("alice");
            assertThat(discoveryCalls).hasValue(1);
            assertThat(jwksCalls).hasValue(1);
        } finally {
            server.stop(0);
        }
    }

    @Test
    void configuredJwksFailureEmitsStructuredDependencyEventWithoutTheToken() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/keys", exchange -> respond(exchange, 503, "temporarily unavailable"));
        server.start();
        Logger logger = (Logger) LoggerFactory.getLogger(JwtVerifier.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        try {
            JwtVerifier verifier = verifier("http://127.0.0.1:" + server.getAddress().getPort() + "/keys");
            String rawToken = sign(signingKey, signingKey.getKeyID(),
                    claims(ISSUER, AUDIENCE, future(), "alice", Map.of()));

            assertInvalid(verifier, rawToken);

            assertThat(appender.list).singleElement().satisfies(event -> {
                assertThat(event.getFormattedMessage()).isEqualTo("Fetching JWKS failed");
                assertThat(event.getFormattedMessage()).doesNotContain(rawToken);
                assertThat(event.getKeyValuePairs())
                        .extracting(pair -> pair.key + "=" + pair.value)
                        .contains("event=dependency_call_failed", "outcome=failure", "dependency=keycloak",
                                "error_code=jwks_fetch_failed");
            });
        } finally {
            logger.detachAppender(appender);
            server.stop(0);
        }
    }

    private JwtVerifier verifier(String jwksUri) {
        return new JwtVerifier(ISSUER, jwksUri, AUDIENCE, new ObjectMapper());
    }

    private JWTClaimsSet claims(
            String issuer, String audience, Date expires, String username, Map<String, ?> realmAccess) {
        JWTClaimsSet.Builder builder = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .audience(audience)
                .expirationTime(expires)
                .claim("name", "Alice Example")
                .claim("email", "alice@example.test")
                .claim("realm_access", realmAccess);
        if (username != null) {
            builder.claim("preferred_username", username);
        }
        return builder.build();
    }

    private Date future() {
        return Date.from(Instant.now().plusSeconds(60));
    }

    private String sign(RSAKey key, String keyId, JWTClaimsSet claims) throws Exception {
        SignedJWT token = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(keyId).build(), claims);
        token.sign(new RSASSASigner(key));
        return token.serialize();
    }

    private void cache(JwtVerifier verifier, RSAKey publicKey) throws Exception {
        var keys = JwtVerifier.class.getDeclaredField("keys");
        keys.setAccessible(true);
        keys.set(verifier, Map.of(publicKey.getKeyID(), publicKey));
    }

    private void assertInvalid(JwtVerifier verifier, String token) {
        assertThatThrownBy(() -> verifier.verify(token))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid token");
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }
}
