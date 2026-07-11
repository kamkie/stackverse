import Fastify from "fastify";
import { SignJWT, exportJWK, generateKeyPair } from "jose";
import { describe, expect, it, vi } from "vitest";
import { loadConfig, type GatewayConfig } from "./config.js";
import { IdpUnavailableError, OidcClient, type FetchLike, type IdTokenVerifier } from "./oidc.js";

interface FetchCall {
  url: string;
  init?: RequestInit;
}

function config(overrides: Record<string, string> = {}): GatewayConfig {
  return loadConfig({
    PORT: "0",
    BACKEND_URL: "http://backend.test",
    REDIS_URL: "redis://redis.test:6379",
    OIDC_ISSUER_URI: "http://idp.test/realms/stackverse",
    OIDC_INTERNAL_ISSUER_URI: "http://keycloak:8080/realms/stackverse",
    OIDC_CLIENT_ID: "stackverse-gateway",
    OIDC_CLIENT_SECRET: "stackverse-secret",
    PUBLIC_URL: "https://stackverse.example/base",
    OTEL_SDK_DISABLED: "true",
    ...overrides,
  });
}

function discovery(overrides: Record<string, string> = {}): Record<string, string> {
  return {
    authorization_endpoint: "http://idp.test/realms/stackverse/protocol/openid-connect/auth",
    token_endpoint: "http://idp.test/realms/stackverse/protocol/openid-connect/token",
    jwks_uri: "http://idp.test/realms/stackverse/protocol/openid-connect/certs",
    end_session_endpoint: "http://idp.test/realms/stackverse/protocol/openid-connect/logout",
    ...overrides,
  };
}

describe("OIDC protocol boundaries", () => {
  it("verifies an ID token against remote JWKS, issuer, audience, and nonce", async () => {
    const { privateKey, publicKey } = await generateKeyPair("RS256");
    const publicJwk = await exportJWK(publicKey);
    Object.assign(publicJwk, { alg: "RS256", kid: "gateway-test-key", use: "sig" });

    const jwksServer = Fastify({ logger: false });
    jwksServer.get("/jwks", async () => ({ keys: [publicJwk] }));
    const address = await jwksServer.listen({ host: "127.0.0.1", port: 0 });
    const gateway = config();
    const token = await new SignJWT({ preferred_username: "alice", nonce: "expected-nonce" })
      .setProtectedHeader({ alg: "RS256", kid: "gateway-test-key" })
      .setIssuer(gateway.oidcIssuerUri)
      .setAudience(gateway.oidcClientId)
      .setIssuedAt()
      .setExpirationTime("5m")
      .sign(privateKey);
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.endsWith("/.well-known/openid-configuration")) {
        return Response.json(discovery({ jwks_uri: `${address}/jwks` }));
      }
      return new Response("not found", { status: 404 });
    }) as FetchLike;
    const client = new OidcClient(gateway, fetchImpl);

    try {
      await expect(client.verifyIdToken(token, "expected-nonce")).resolves.toMatchObject({
        preferred_username: "alice",
        nonce: "expected-nonce",
        iss: gateway.oidcIssuerUri,
        aud: gateway.oidcClientId,
      });
      await expect(client.verifyIdToken(token, "wrong-nonce")).rejects.toThrow("nonce_mismatch");
    } finally {
      await jwksServer.close();
    }
  });

  it("supports an injected verifier without fetching discovery or JWKS", async () => {
    const fetchImpl = vi.fn() as FetchLike;
    const verifier = vi.fn(async () => ({ preferred_username: "injected-user" })) as IdTokenVerifier;
    const client = new OidcClient(config(), fetchImpl, verifier);

    await expect(client.verifyIdToken("opaque-id-token", "expected-nonce")).resolves.toEqual({
      preferred_username: "injected-user",
    });
    expect(verifier).toHaveBeenCalledWith("opaque-id-token", "expected-nonce");
    expect(fetchImpl).not.toHaveBeenCalled();
  });

  it("rejects failed authorization-code exchanges without exposing the endpoint body", async () => {
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.endsWith("/.well-known/openid-configuration")) return Response.json(discovery());
      if (url.endsWith("/protocol/openid-connect/token")) {
        return new Response("client_secret=must-not-escape", { status: 502 });
      }
      return new Response("not found", { status: 404 });
    }) as FetchLike;
    const client = new OidcClient(config(), fetchImpl);

    await expect(client.exchangeCode("code", "verifier")).rejects.toThrow("token_endpoint_502");
  });

  it("parses a complete successful refresh response and sends the server-side client credentials", async () => {
    const calls: FetchCall[] = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString();
      calls.push({ url, init });
      if (url.endsWith("/.well-known/openid-configuration")) return Response.json(discovery());
      if (url.endsWith("/protocol/openid-connect/token")) {
        return Response.json({
          access_token: "new-access",
          refresh_token: "new-refresh",
          id_token: "new-id-token",
          expires_in: 120,
        });
      }
      return new Response("not found", { status: 404 });
    }) as FetchLike;
    const client = new OidcClient(config(), fetchImpl);

    await expect(client.refresh("old-refresh")).resolves.toEqual({
      accessToken: "new-access",
      refreshToken: "new-refresh",
      idToken: "new-id-token",
      expiresIn: 120,
    });
    const tokenCall = calls.find((call) => call.url.endsWith("/protocol/openid-connect/token"));
    const body = tokenCall?.init?.body as URLSearchParams;
    expect(body.get("grant_type")).toBe("refresh_token");
    expect(body.get("refresh_token")).toBe("old-refresh");
    expect(body.get("client_id")).toBe("stackverse-gateway");
    expect(body.get("client_secret")).toBe("stackverse-secret");
  });

  it("distinguishes an authoritative refresh rejection from IdP unavailability", async () => {
    const rejectedFetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.endsWith("/.well-known/openid-configuration")) return Response.json(discovery());
      return new Response("revoked", { status: 401 });
    }) as FetchLike;
    await expect(new OidcClient(config(), rejectedFetch).refresh("revoked-refresh")).resolves.toBeNull();

    const unavailableFetch = vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.endsWith("/.well-known/openid-configuration")) return Response.json(discovery());
      return new Response("busy", { status: 429 });
    }) as FetchLike;
    await expect(new OidcClient(config(), unavailableFetch).refresh("still-valid-refresh")).rejects.toBeInstanceOf(
      IdpUnavailableError,
    );
  });

  it("keeps the session semantics unavailable on token-network and discovery failures", async () => {
    const tokenNetworkFailure = vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.endsWith("/.well-known/openid-configuration")) return Response.json(discovery());
      throw new TypeError("connection reset");
    }) as FetchLike;
    await expect(new OidcClient(config(), tokenNetworkFailure).refresh("refresh-token")).rejects.toBeInstanceOf(
      IdpUnavailableError,
    );

    const discoveryFailure = vi.fn(async () => {
      throw new TypeError("discovery offline");
    }) as FetchLike;
    await expect(new OidcClient(config(), discoveryFailure).refresh("refresh-token")).rejects.toBeInstanceOf(
      IdpUnavailableError,
    );
  });

  it("treats invalid JSON from a successful token endpoint as an outage", async () => {
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.endsWith("/.well-known/openid-configuration")) return Response.json(discovery());
      return new Response("{not-json", { status: 200, headers: { "content-type": "application/json" } });
    }) as FetchLike;

    await expect(new OidcClient(config(), fetchImpl).refresh("refresh-token")).rejects.toBeInstanceOf(
      IdpUnavailableError,
    );
  });

  it("uses safe issuer-relative fallbacks when discovery omits optional endpoints", async () => {
    const calls: FetchCall[] = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString();
      calls.push({ url, init });
      if (url.endsWith("/.well-known/openid-configuration")) return Response.json({});
      if (url.endsWith("/protocol/openid-connect/token")) return Response.json({ access_token: "access" });
      if (url.endsWith("/protocol/openid-connect/logout")) return new Response(null, { status: 204 });
      return new Response("not found", { status: 404 });
    }) as FetchLike;
    const client = new OidcClient(config(), fetchImpl);

    const authorizationUrl = new URL(await client.authorizationUrl("state", "verifier", "nonce"));
    expect(authorizationUrl.origin).toBe("http://idp.test");
    expect(authorizationUrl.pathname).toBe("/realms/stackverse/protocol/openid-connect/auth");

    await expect(client.exchangeCode("code", "verifier")).resolves.toEqual({ accessToken: "access", expiresIn: 300 });
    await expect(client.logout("refresh-token")).resolves.toBeUndefined();
    expect(
      calls.some((call) => call.url === "http://keycloak:8080/realms/stackverse/protocol/openid-connect/token"),
    ).toBe(true);
    expect(
      calls.some((call) => call.url === "http://keycloak:8080/realms/stackverse/protocol/openid-connect/logout"),
    ).toBe(true);
  });

  it("keeps local logout successful when the IdP cannot be reached", async () => {
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.endsWith("/.well-known/openid-configuration")) return Response.json(discovery());
      throw new TypeError("logout endpoint offline");
    }) as FetchLike;

    await expect(new OidcClient(config(), fetchImpl).logout("refresh-token")).resolves.toBeUndefined();
  });
});
