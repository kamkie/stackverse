import { describe, expect, it, vi } from "vitest";
import { loadConfig } from "./config.js";
import { IdpUnavailableError, OidcClient, pkceChallenge, usernameFromIdToken, type FetchLike } from "./oidc.js";

interface FetchCall {
  url: string;
  init?: RequestInit;
}

function config() {
  return loadConfig({
    PORT: "0",
    BACKEND_URL: "http://backend.test",
    REDIS_URL: "redis://redis.test:6379",
    OIDC_ISSUER_URI: "http://idp.test/realms/stackverse/",
    OIDC_INTERNAL_ISSUER_URI: "http://keycloak:8080/realms/stackverse/",
    OIDC_CLIENT_ID: "stackverse-gateway",
    OIDC_CLIENT_SECRET: "stackverse-secret",
    PUBLIC_URL: "http://localhost:8000",
    OTEL_SDK_DISABLED: "true",
  });
}

function discoveryDocument() {
  return {
    authorization_endpoint: "http://keycloak:8080/realms/stackverse/protocol/openid-connect/auth",
    token_endpoint: "http://idp.test/realms/stackverse/protocol/openid-connect/token",
    jwks_uri: "http://idp.test/realms/stackverse/protocol/openid-connect/certs",
    end_session_endpoint: "http://keycloak:8080/realms/stackverse/protocol/openid-connect/logout",
  };
}

describe("OidcClient", () => {
  it("keeps browser redirects public while using the internal issuer for server calls", async () => {
    const calls: FetchCall[] = [];
    const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const url = input.toString();
      calls.push({ url, init });
      if (url.endsWith("/.well-known/openid-configuration")) {
        return Response.json(discoveryDocument());
      }
      if (url.endsWith("/protocol/openid-connect/token")) {
        return Response.json({ access_token: "access-token" });
      }
      return new Response("not found", { status: 404 });
    }) as FetchLike;
    const client = new OidcClient(config(), fetchImpl);

    const authorization = new URL(await client.authorizationUrl("state", "verifier", "nonce"));
    expect(calls[0]?.url).toBe("http://keycloak:8080/realms/stackverse/.well-known/openid-configuration");
    expect(authorization.origin).toBe("http://idp.test");
    expect(authorization.pathname).toBe("/realms/stackverse/protocol/openid-connect/auth");
    expect(authorization.searchParams.get("code_challenge")).toBe(pkceChallenge("verifier"));
    expect(authorization.searchParams.get("redirect_uri")).toBe("http://localhost:8000/auth/callback");

    const tokens = await client.exchangeCode("auth-code", "verifier");
    expect(tokens).toEqual({ accessToken: "access-token", expiresIn: 300 });

    const tokenCall = calls.find((call) => call.url.endsWith("/protocol/openid-connect/token"));
    expect(tokenCall?.url).toBe("http://keycloak:8080/realms/stackverse/protocol/openid-connect/token");
    const body = tokenCall?.init?.body as URLSearchParams;
    expect(body.get("grant_type")).toBe("authorization_code");
    expect(body.get("code")).toBe("auth-code");
    expect(body.get("client_secret")).toBe("stackverse-secret");
  });

  it("treats malformed refresh responses as an IdP outage", async () => {
    const fetchImpl = vi.fn(async (input: RequestInfo | URL) => {
      const url = input.toString();
      if (url.endsWith("/.well-known/openid-configuration")) {
        return Response.json(discoveryDocument());
      }
      if (url.endsWith("/protocol/openid-connect/token")) {
        return Response.json({ refresh_token: "new-refresh" });
      }
      return new Response("not found", { status: 404 });
    }) as FetchLike;
    const client = new OidcClient(config(), fetchImpl);

    await expect(client.refresh("refresh-token")).rejects.toBeInstanceOf(IdpUnavailableError);
  });

  it("extracts usernames from supported ID token claims", () => {
    expect(usernameFromIdToken({ preferred_username: "demo" })).toBe("demo");
    expect(usernameFromIdToken({ name: "Display Name" })).toBe("Display Name");
    expect(usernameFromIdToken({ sub: "subject-id" })).toBe("subject-id");
    expect(() => usernameFromIdToken({ email: "demo@example.com" })).toThrow("id_token_missing_username");
  });
});
