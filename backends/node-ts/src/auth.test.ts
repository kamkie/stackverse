import Fastify from "fastify";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  query: vi.fn(),
  jwtVerify: vi.fn(),
  logEvent: vi.fn(),
  localize: vi.fn(async () => "Your account is blocked."),
  resolveLanguage: vi.fn(async () => "en"),
}));

vi.mock("./db.js", () => ({ pool: { query: mocks.query } }));
vi.mock("./logging.js", () => ({ logEvent: mocks.logEvent }));
vi.mock("./i18n.js", () => ({ localize: mocks.localize, resolveLanguage: mocks.resolveLanguage }));
vi.mock("jose", async (importOriginal) => ({
  ...(await importOriginal<typeof import("jose")>()),
  createRemoteJWKSet: vi.fn(() => "keys"),
  jwtVerify: mocks.jwtVerify,
}));

import { registerAuth, requireCaller, requireRole, toMeResponse } from "./auth.js";

describe("authentication and authorization boundary", () => {
  beforeEach(() => vi.clearAllMocks());

  it("leaves anonymous requests unauthenticated and role checks deny them", async () => {
    const app = Fastify();
    registerAuth(app);
    app.get("/caller", async (request) => ({ caller: request.caller }));
    const response = await app.inject("/caller");
    expect(response.json()).toEqual({ caller: null });
    expect(() => requireCaller({ caller: null } as never)).toThrowError("Authentication is required.");
    await app.close();
  });

  it("rejects invalid bearer tokens without persisting or leaking the token", async () => {
    mocks.jwtVerify.mockRejectedValueOnce(Object.assign(new Error("bad token"), { code: "ERR_JWT_EXPIRED" }));
    const app = Fastify();
    registerAuth(app);
    app.get("/private", async (request) => toMeResponse(requireCaller(request)));
    const response = await app.inject({ url: "/private", headers: { authorization: "Bearer super-secret" } });
    expect(response.statusCode).toBe(401);
    expect(response.json()).toMatchObject({ status: 401, title: "Unauthorized" });
    expect(mocks.query).not.toHaveBeenCalled();
    expect(mocks.logEvent).toHaveBeenCalledWith("info", "jwt_validation_failed", "failure", expect.any(String), {
      error_code: "err_jwt_expired",
    });
    expect(JSON.stringify(mocks.logEvent.mock.calls)).not.toContain("super-secret");
    await app.close();
  });

  it("persists last-seen state and exposes only application roles", async () => {
    mocks.jwtVerify.mockResolvedValueOnce({
      payload: {
        preferred_username: "alice",
        name: "Alice",
        email: "alice@example.test",
        realm_access: { roles: ["offline_access", "admin", "moderator"] },
      },
    });
    mocks.query.mockResolvedValueOnce({ rows: [{ status: "active" }] });
    const app = Fastify();
    registerAuth(app);
    app.get("/me", async (request) => toMeResponse(requireRole(request, "admin")));
    const response = await app.inject({ url: "/me", headers: { authorization: "Bearer valid" } });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({
      username: "alice",
      name: "Alice",
      email: "alice@example.test",
      roles: ["admin", "moderator"],
    });
    expect(mocks.query).toHaveBeenCalledWith(expect.stringContaining("on conflict"), ["alice", expect.any(Date)]);
    await app.close();
  });

  it("blocks persisted blocked accounts before handlers and logs no free-form fields", async () => {
    mocks.jwtVerify.mockResolvedValueOnce({ payload: { preferred_username: "blocked", realm_access: { roles: [] } } });
    mocks.query.mockResolvedValueOnce({ rows: [{ status: "blocked" }] });
    const app = Fastify();
    registerAuth(app);
    app.get("/private", async () => ({ reached: true }));
    const response = await app.inject({ url: "/private?lang=en", headers: { authorization: "Bearer valid" } });
    expect(response.statusCode).toBe(403);
    expect(response.json()).toMatchObject({ status: 403, detail: "Your account is blocked." });
    expect(mocks.logEvent).toHaveBeenCalledWith("warn", "blocked_user_rejected", "denied", expect.any(String), {
      actor: "blocked",
    });
    await app.close();
  });
});
