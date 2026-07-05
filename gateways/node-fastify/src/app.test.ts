import { mkdir, mkdtemp, rm, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import path from "node:path";
import { describe, expect, it, vi } from "vitest";
import type { FastifyInstance } from "fastify";
import { buildApp } from "./app.js";
import { loadConfig, type GatewayConfig } from "./config.js";
import type { OidcClient } from "./oidc.js";
import { CONTENT_SECURITY_POLICY, STRICT_TRANSPORT_SECURITY } from "./security.js";
import { MemorySessionStore, type GatewaySession } from "./session-store.js";

interface FetchCall {
  url: string;
  init?: RequestInit & { duplex?: "half" };
}

const BACKEND_ORIGIN = new URL("http://backend.test").origin;
const FRONTEND_ORIGIN = new URL("http://frontend.test").origin;

function testConfig(overrides: Record<string, string> = {}): GatewayConfig {
  return loadConfig({
    PORT: "0",
    BACKEND_URL: "http://backend.test",
    FRONTEND_URL: "http://frontend.test",
    REDIS_URL: "redis://redis.test:6379",
    OIDC_ISSUER_URI: "http://idp.test/realms/stackverse",
    OIDC_CLIENT_ID: "stackverse-gateway",
    OIDC_CLIENT_SECRET: "stackverse-secret",
    PUBLIC_URL: "http://localhost:8000",
    OTEL_SDK_DISABLED: "true",
    ...overrides,
  });
}

async function withApp(
  test: (app: FastifyInstance, store: MemorySessionStore, calls: FetchCall[]) => Promise<void>,
  config: GatewayConfig = testConfig(),
  fetchHandler?: (url: string, init?: RequestInit & { duplex?: "half" }) => Response | Promise<Response>,
): Promise<void> {
  const store = new MemorySessionStore();
  const calls: FetchCall[] = [];
  const fetchImpl = vi.fn(async (input: RequestInfo | URL, init?: RequestInit & { duplex?: "half" }) => {
    const url = input.toString();
    calls.push({ url, init });
    if (fetchHandler) return fetchHandler(url, init);
    return defaultFetch(url);
  });
  const app = await buildApp({ config, sessionStore: store, fetchImpl: fetchImpl as typeof fetch });
  try {
    await test(app, store, calls);
  } finally {
    await app.close();
  }
}

function defaultFetch(url: string): Response {
  if (url.endsWith("/.well-known/openid-configuration")) {
    return Response.json({
      authorization_endpoint: "http://idp.test/realms/stackverse/protocol/openid-connect/auth",
      token_endpoint: "http://idp.test/realms/stackverse/protocol/openid-connect/token",
      jwks_uri: "http://idp.test/realms/stackverse/protocol/openid-connect/certs",
      end_session_endpoint: "http://idp.test/realms/stackverse/protocol/openid-connect/logout",
    });
  }
  if (hasOrigin(url, BACKEND_ORIGIN)) {
    return Response.json({ ok: true }, { headers: { "cache-control": "no-cache", etag: "\"bundle-v1\"" } });
  }
  if (hasOrigin(url, FRONTEND_ORIGIN)) {
    return new Response("<h1>Stackverse frontend stub</h1>", { headers: { "content-type": "text/html" } });
  }
  return new Response("not found", { status: 404 });
}

function hasOrigin(url: string, origin: string): boolean {
  return new URL(url).origin === origin;
}

function cookieValue(response: { headers: Record<string, string | string[] | undefined> }, name: string): string {
  const values = response.headers["set-cookie"];
  const cookies = Array.isArray(values) ? values : values ? [values] : [];
  const match = cookies.find((cookie) => cookie.startsWith(`${name}=`));
  if (!match) throw new Error(`missing ${name} Set-Cookie`);
  return match.slice(name.length + 1, match.indexOf(";"));
}

function header(headers: Headers | undefined, name: string): string | null {
  return headers?.get(name) ?? null;
}

function freshSession(overrides: Partial<GatewaySession> = {}): GatewaySession {
  const now = Date.now();
  return {
    username: "demo",
    accessToken: "access-token",
    refreshToken: "refresh-token",
    expiresAt: now + 60_000,
    createdAt: now,
    updatedAt: now,
    ...overrides,
  };
}

describe("node-fastify gateway", () => {
  it("reports an anonymous auth session without a server session", async () => {
    await withApp(async (app) => {
      const response = await app.inject({ method: "GET", url: "/auth/session" });

      expect(response.statusCode).toBe(200);
      expect(response.json()).toEqual({ authenticated: false });
      expect(cookieValue(response, "XSRF-TOKEN")).toHaveLength(22);
    });
  });

  it("builds an OIDC authorization-code redirect with PKCE", async () => {
    await withApp(async (app, _store, calls) => {
      const response = await app.inject({ method: "GET", url: "/auth/login" });

      expect(response.statusCode).toBe(302);
      const location = response.headers.location;
      expect(location).toContain("http://idp.test/realms/stackverse/protocol/openid-connect/auth");
      expect(location).toContain("response_type=code");
      expect(location).toContain("code_challenge_method=S256");
      expect(location).toContain(`redirect_uri=${encodeURIComponent("http://localhost:8000/auth/callback")}`);
      expect(calls[0]?.url).toBe("http://idp.test/realms/stackverse/.well-known/openid-configuration");
    });
  });

  it("redirects failed callbacks home without creating a session", async () => {
    await withApp(async (app) => {
      const response = await app.inject({ method: "GET", url: "/auth/callback?error=access_denied&state=whatever" });

      expect(response.statusCode).toBe(302);
      expect(response.headers.location).toBe("/");
      expect(response.headers["set-cookie"]?.toString()).not.toContain("stackverse_session=");
    });
  });

  it("creates a gateway session after a successful OIDC callback", async () => {
    const config = testConfig();
    const store = new MemorySessionStore();
    const oidcClient = {
      authorizationUrl: vi.fn(async (state: string) => `http://idp.test/auth?state=${state}`),
      exchangeCode: vi.fn(async (code: string, codeVerifier: string) => {
        expect(code).toBe("auth-code");
        expect(codeVerifier).toHaveLength(43);
        return {
          accessToken: "callback-access",
          refreshToken: "callback-refresh",
          idToken: "callback-id",
          expiresIn: 120,
        };
      }),
      verifyIdToken: vi.fn(async (idToken: string, nonce: string) => {
        expect(idToken).toBe("callback-id");
        expect(nonce).toHaveLength(32);
        return { preferred_username: "alice" };
      }),
      logout: vi.fn(async () => undefined),
    } as unknown as OidcClient;
    const app = await buildApp({ config, sessionStore: store, oidcClient });

    try {
      const login = await app.inject({ method: "GET", url: "/auth/login" });
      const state = new URL(login.headers.location as string).searchParams.get("state");
      expect(state).toHaveLength(32);

      const callback = await app.inject({ method: "GET", url: `/auth/callback?code=auth-code&state=${state}` });

      expect(callback.statusCode).toBe(302);
      expect(callback.headers.location).toBe("/");
      const sessionId = cookieValue(callback, "stackverse_session");
      const session = await store.getSession(sessionId);
      expect(session).toMatchObject({
        username: "alice",
        accessToken: "callback-access",
        refreshToken: "callback-refresh",
        idToken: "callback-id",
      });

      const sessionResponse = await app.inject({
        method: "GET",
        url: "/auth/session",
        headers: { cookie: `stackverse_session=${sessionId}` },
      });
      expect(sessionResponse.json()).toEqual({ authenticated: true, username: "alice" });
      expect(oidcClient.exchangeCode).toHaveBeenCalledTimes(1);
      expect(oidcClient.verifyIdToken).toHaveBeenCalledTimes(1);
    } finally {
      await app.close();
    }
  });

  it("relays anonymous api requests without client-supplied credentials", async () => {
    await withApp(async (app, _store, calls) => {
      const response = await app.inject({
        method: "GET",
        url: "/api/v2/bookmarks?visibility=public",
        headers: {
          authorization: "Bearer forged",
          cookie: "stackverse_session=missing; XSRF-TOKEN=token",
        },
      });

      expect(response.statusCode).toBe(200);
      const backend = calls.find((call) => hasOrigin(call.url, BACKEND_ORIGIN));
      expect(backend?.url).toBe("http://backend.test/api/v2/bookmarks?visibility=public");
      expect(header(backend?.init?.headers as Headers, "authorization")).toBeNull();
      expect(header(backend?.init?.headers as Headers, "cookie")).toBeNull();
    });
  });

  it("relays a server-side session access token and strips gateway-only headers", async () => {
    await withApp(async (app, store, calls) => {
      await store.saveSession("session-id", freshSession(), 3600);

      const response = await app.inject({
        method: "GET",
        url: "/api/v1/bookmarks",
        headers: {
          cookie: "stackverse_session=session-id; XSRF-TOKEN=token",
          "x-xsrf-token": "token",
        },
      });

      expect(response.statusCode).toBe(200);
      const backend = calls.find((call) => hasOrigin(call.url, BACKEND_ORIGIN));
      const headers = backend?.init?.headers as Headers;
      expect(header(headers, "authorization")).toBe("Bearer access-token");
      expect(header(headers, "cookie")).toBeNull();
      expect(header(headers, "x-xsrf-token")).toBeNull();
    });
  });

  it("requires double-submit csrf and same-origin browser signals for state-changing api calls", async () => {
    await withApp(async (app) => {
      const tokenResponse = await app.inject({ method: "GET", url: "/auth/session" });
      const xsrf = cookieValue(tokenResponse, "XSRF-TOKEN");

      const missing = await app.inject({ method: "POST", url: "/api/v1/bookmarks", payload: "{}" });
      expect(missing.statusCode).toBe(403);
      expect(missing.headers["content-type"]).toContain("application/problem+json");

      const foreignOrigin = await app.inject({
        method: "POST",
        url: "/api/v1/bookmarks",
        payload: "{}",
        headers: { cookie: `XSRF-TOKEN=${xsrf}`, "x-xsrf-token": xsrf, origin: "https://evil.example" },
      });
      expect(foreignOrigin.statusCode).toBe(403);
      expect(foreignOrigin.body).toContain("Cross-origin state-changing requests are not supported.");

      const sameSite = await app.inject({
        method: "POST",
        url: "/api/v1/bookmarks",
        payload: "{}",
        headers: { cookie: `XSRF-TOKEN=${xsrf}`, "x-xsrf-token": xsrf, "sec-fetch-site": "same-site" },
      });
      expect(sameSite.statusCode).toBe(403);

      const valid = await app.inject({
        method: "POST",
        url: "/api/v1/bookmarks",
        payload: "{}",
        headers: { cookie: `XSRF-TOKEN=${xsrf}`, "x-xsrf-token": xsrf, origin: "http://localhost:8000" },
      });
      expect(valid.statusCode).toBe(200);
    });
  });

  it("scopes browser security headers without rewriting api cache headers", async () => {
    await withApp(async (app) => {
      const spa = await app.inject({ method: "GET", url: "/" });
      expect(spa.headers["x-content-type-options"]).toBe("nosniff");
      expect(spa.headers["content-security-policy"]).toBe(CONTENT_SECURITY_POLICY);
      expect(spa.headers["x-frame-options"]).toBe("DENY");
      expect(spa.headers["strict-transport-security"]).toBeUndefined();

      const api = await app.inject({ method: "GET", url: "/api/v1/messages/bundle" });
      expect(api.headers["x-content-type-options"]).toBe("nosniff");
      expect(api.headers["content-security-policy"]).toBeUndefined();
      expect(api.headers["cache-control"]).toBe("no-cache");
      expect(api.headers.etag).toBe("\"bundle-v1\"");
    });
  });

  it("does not forward stale compression framing after fetch decodes an upstream body", async () => {
    await withApp(async (app) => {
      const response = await app.inject({ method: "GET", url: "/api/v1/messages/bundle" });

      expect(response.statusCode).toBe(200);
      expect(response.headers["content-encoding"]).toBeUndefined();
      expect(response.headers["content-length"]).toBe(String(Buffer.byteLength(response.body)));
      expect(response.body).toBe("{\"ok\":true}");
    }, testConfig(), (url) => {
      if (hasOrigin(url, BACKEND_ORIGIN)) {
        return new Response("{\"ok\":true}", {
          headers: {
            "content-encoding": "gzip",
            "content-length": "999",
          },
        });
      }
      return defaultFetch(url);
    });
  });

  it("passes through API responses that must not carry a body", async () => {
    await withApp(async (app) => {
      const notModified = await app.inject({ method: "GET", url: "/api/v1/messages/bundle" });
      expect(notModified.statusCode).toBe(304);
      expect(notModified.headers.etag).toBe("\"bundle-v1\"");
      expect(notModified.body).toBe("");

      const head = await app.inject({ method: "HEAD", url: "/api/v1/messages/bundle" });
      expect(head.statusCode).toBe(200);
      expect(head.headers["cache-control"]).toBe("no-cache");
      expect(head.body).toBe("");
    }, testConfig(), (url, init) => {
      if (hasOrigin(url, BACKEND_ORIGIN) && init?.method === "GET") {
        return new Response(null, {
          status: 304,
          headers: { "cache-control": "no-cache", etag: "\"bundle-v1\"" },
        });
      }
      return defaultFetch(url);
    });
  });

  it("returns an upstream problem document when backend proxying fails", async () => {
    await withApp(async (app) => {
      const response = await app.inject({ method: "GET", url: "/api/v1/bookmarks" });

      expect(response.statusCode).toBe(502);
      expect(response.headers["content-type"]).toContain("application/problem+json");
      expect(response.json()).toMatchObject({
        type: "about:blank",
        title: "Bad Gateway",
        status: 502,
        detail: "The upstream service is unavailable.",
      });
    }, testConfig(), (url) => {
      if (hasOrigin(url, BACKEND_ORIGIN)) {
        throw new Error("backend offline");
      }
      return defaultFetch(url);
    });
  });

  it("emits hsts only when PUBLIC_URL is https", async () => {
    await withApp(async (app) => {
      const response = await app.inject({ method: "GET", url: "/auth/session" });
      expect(response.headers["strict-transport-security"]).toBe(STRICT_TRANSPORT_SECURITY);
    }, testConfig({ PUBLIC_URL: "https://stackverse.example" }));
  });

  it("destroys a session and degrades to anonymous when the IdP rejects refresh", async () => {
    await withApp(async (app, store, calls) => {
      await store.saveSession("session-id", freshSession({ expiresAt: Date.now() - 60_000 }), 3600);

      const response = await app.inject({
        method: "GET",
        url: "/api/v1/bookmarks",
        headers: { cookie: "stackverse_session=session-id; XSRF-TOKEN=token" },
      });

      expect(response.statusCode).toBe(200);
      expect(await store.getSession("session-id")).toBeNull();
      const backend = calls.findLast((call) => hasOrigin(call.url, BACKEND_ORIGIN));
      expect(header(backend?.init?.headers as Headers, "authorization")).toBeNull();
    }, testConfig(), (url) => {
      if (url.endsWith("/.well-known/openid-configuration")) return defaultFetch(url);
      if (url.endsWith("/protocol/openid-connect/token")) return new Response("bad grant", { status: 400 });
      return defaultFetch(url);
    });
  });

  it("keeps the session and returns 503 when the IdP is unavailable during refresh", async () => {
    await withApp(async (app, store, calls) => {
      await store.saveSession("session-id", freshSession({ expiresAt: Date.now() - 60_000 }), 3600);

      const response = await app.inject({
        method: "GET",
        url: "/api/v1/bookmarks",
        headers: { cookie: "stackverse_session=session-id; XSRF-TOKEN=token" },
      });

      expect(response.statusCode).toBe(503);
      expect(response.headers["content-type"]).toContain("application/problem+json");
      expect(await store.getSession("session-id")).not.toBeNull();
      expect(calls.some((call) => hasOrigin(call.url, BACKEND_ORIGIN))).toBe(false);
    }, testConfig(), (url) => {
      if (url.endsWith("/.well-known/openid-configuration")) return defaultFetch(url);
      if (url.endsWith("/protocol/openid-connect/token")) return new Response("idp down", { status: 503 });
      return defaultFetch(url);
    });
  });

  it("updates Redis session data after a successful refresh", async () => {
    await withApp(async (app, store, calls) => {
      await store.saveSession("session-id", freshSession({ accessToken: "old", expiresAt: Date.now() - 60_000 }), 3600);

      const response = await app.inject({
        method: "GET",
        url: "/api/v1/bookmarks",
        headers: { cookie: "stackverse_session=session-id; XSRF-TOKEN=token" },
      });

      expect(response.statusCode).toBe(200);
      const updated = await store.getSession("session-id");
      expect(updated?.accessToken).toBe("new-access");
      expect(updated?.refreshToken).toBe("new-refresh");
      const backend = calls.findLast((call) => hasOrigin(call.url, BACKEND_ORIGIN));
      expect(header(backend?.init?.headers as Headers, "authorization")).toBe("Bearer new-access");
    }, testConfig(), (url) => {
      if (url.endsWith("/.well-known/openid-configuration")) return defaultFetch(url);
      if (url.endsWith("/protocol/openid-connect/token")) {
        return Response.json({ access_token: "new-access", refresh_token: "new-refresh", expires_in: 300 });
      }
      return defaultFetch(url);
    });
  });

  it("retries OIDC discovery after a transient failure", async () => {
    let discoveryAttempts = 0;
    await withApp(async (app) => {
      const first = await app.inject({ method: "GET", url: "/auth/login" });
      expect(first.statusCode).toBe(503);

      const second = await app.inject({ method: "GET", url: "/auth/login" });
      expect(second.statusCode).toBe(302);
      expect(second.headers.location).toContain("/protocol/openid-connect/auth");
      expect(discoveryAttempts).toBe(2);
    }, testConfig(), (url) => {
      if (url.endsWith("/.well-known/openid-configuration")) {
        discoveryAttempts += 1;
        if (discoveryAttempts === 1) {
          throw new Error("connection reset");
        }
      }
      return defaultFetch(url);
    });
  });

  it("destroys local session before best-effort IdP logout", async () => {
    await withApp(async (app, store, calls) => {
      await store.saveSession("session-id", freshSession(), 3600);

      const response = await app.inject({
        method: "POST",
        url: "/auth/logout",
        headers: { cookie: "stackverse_session=session-id" },
      });

      expect(response.statusCode).toBe(204);
      expect(await store.getSession("session-id")).toBeNull();
      expect(calls.some((call) => call.url.endsWith("/protocol/openid-connect/logout"))).toBe(true);
    });
  });

  it("proxies frontend routes without leaking gateway cookies", async () => {
    await withApp(async (app, _store, calls) => {
      const response = await app.inject({
        method: "GET",
        url: "/admin/users",
        headers: { cookie: "stackverse_session=session-id; XSRF-TOKEN=token" },
      });

      expect(response.statusCode).toBe(200);
      expect(response.body).toContain("Stackverse frontend stub");
      const frontend = calls.find((call) => hasOrigin(call.url, FRONTEND_ORIGIN));
      expect(frontend?.url).toBe("http://frontend.test/admin/users");
      expect(header(frontend?.init?.headers as Headers, "cookie")).toBeNull();
    });
  });

  it("serves the static SPA fallback for unknown and unsafe paths", async () => {
    const root = await mkdtemp(path.join(tmpdir(), "stackverse-node-fastify-spa-"));
    await mkdir(path.join(root, "assets"));
    await writeFile(path.join(root, "index.html"), "<main>fallback shell</main>");
    await writeFile(path.join(root, "assets", "app.js"), "window.stackverse = true;");

    try {
      await withApp(async (app) => {
        const asset = await app.inject({ method: "GET", url: "/assets/app.js" });
        expect(asset.statusCode).toBe(200);
        expect(asset.headers["content-type"]).toBe("text/javascript; charset=utf-8");
        expect(asset.body).toBe("window.stackverse = true;");

        const fallback = await app.inject({ method: "GET", url: "/admin/users" });
        expect(fallback.statusCode).toBe(200);
        expect(fallback.headers["content-type"]).toBe("text/html; charset=utf-8");
        expect(fallback.body).toBe("<main>fallback shell</main>");

        const unknownPath = await app.inject({ method: "GET", url: "/%2e%2e/secret.txt" });
        expect(unknownPath.statusCode).toBe(200);
        expect(unknownPath.body).toBe("<main>fallback shell</main>");

        if (process.platform === "win32") {
          const driveQualifiedPath = await app.inject({ method: "GET", url: "/%43:%5CWindows%5Cwin.ini" });
          expect(driveQualifiedPath.statusCode).toBe(200);
          expect(driveQualifiedPath.body).toBe("<main>fallback shell</main>");
        }

        const unsupportedMethod = await app.inject({ method: "POST", url: "/admin/users" });
        expect(unsupportedMethod.statusCode).toBe(404);
        expect(unsupportedMethod.headers["content-type"]).toContain("application/problem+json");
      }, testConfig({ FRONTEND_URL: "", SPA_ROOT: root }));
    } finally {
      await rm(root, { force: true, recursive: true });
    }
  });
});
