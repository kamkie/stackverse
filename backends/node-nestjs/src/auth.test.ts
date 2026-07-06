import { beforeEach, describe, expect, it, vi } from "vitest";
import Fastify from "fastify";

const logEventMock = vi.hoisted(() => vi.fn());

vi.mock("./logging.js", () => ({
  logEvent: logEventMock,
}));

import { registerFastifyAuth, requireCaller, requireRole, toMeResponse, type Caller } from "./auth.js";
import { sendProblemForError } from "./problem.filter.js";
import { ForbiddenProblem, UnauthorizedProblem } from "./problems.js";

beforeEach(() => {
  logEventMock.mockReset();
});

const requestWith = (caller: Caller | null) => ({ caller }) as never;

describe("auth request helpers", () => {
  it("requires an authenticated caller for private endpoints", () => {
    expect(() => requireCaller(requestWith(null))).toThrow(UnauthorizedProblem);

    const caller = { username: "demo", roles: [] };
    expect(requireCaller(requestWith(caller))).toBe(caller);
  });

  it("checks for the exact role carried by the token", () => {
    const caller = { username: "mod", roles: ["moderator"] };

    expect(requireRole(requestWith(caller), "moderator")).toBe(caller);
    expect(() => requireRole(requestWith(caller), "admin")).toThrow(ForbiddenProblem);
    expect(logEventMock).toHaveBeenCalledWith(
      "info",
      "authz_denied",
      "denied",
      "Denied a request lacking the required role",
      { actor: "mod" },
    );
  });

  it("exposes only Stackverse application roles in a stable order", () => {
    expect(
      toMeResponse({
        username: "admin",
        name: "Admin User",
        email: "admin@example.com",
        roles: ["offline_access", "admin", "moderator", "uma_authorization"],
      }),
    ).toEqual({
      username: "admin",
      name: "Admin User",
      email: "admin@example.com",
      roles: ["admin", "moderator"],
    });
  });
});

describe("Fastify auth hook", () => {
  it("defaults every request caller to null before handlers run", async () => {
    const app = Fastify({ logger: false });
    registerFastifyAuth(app);
    app.get("/caller", (request) => ({ callerIsNull: request.caller === null }));

    try {
      const response = await app.inject({ method: "GET", url: "/caller" });

      expect(response.statusCode).toBe(200);
      expect(response.json()).toEqual({ callerIsNull: true });
    } finally {
      await app.close();
    }
  });

  it("rejects an invalid bearer token before malformed JSON is parsed", async () => {
    const app = Fastify({ logger: false });
    registerFastifyAuth(app);
    app.setErrorHandler(async (error, request, reply) => sendProblemForError(error, request, reply));
    app.post("/api/v1/bookmarks", () => {
      throw new Error("handler should not run");
    });

    try {
      const response = await app.inject({
        method: "POST",
        url: "/api/v1/bookmarks",
        headers: {
          authorization: "Bearer not-a-jwt",
          "content-type": "application/json",
        },
        payload: '{"url":',
      });

      expect(response.statusCode).toBe(401);
      expect(response.headers["content-type"]).toContain("application/problem+json");
      expect(response.json()).toMatchObject({
        type: "about:blank",
        title: "Unauthorized",
        status: 401,
        detail: "Missing or invalid bearer token.",
      });
    } finally {
      await app.close();
    }
  });
});
