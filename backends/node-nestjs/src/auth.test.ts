import { beforeEach, describe, expect, it, vi } from "vitest";

const logEventMock = vi.hoisted(() => vi.fn());

vi.mock("./logging.js", () => ({
  logEvent: logEventMock,
}));

import { requireCaller, requireRole, toMeResponse, type Caller } from "./auth.js";
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
