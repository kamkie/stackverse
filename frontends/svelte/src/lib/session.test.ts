import { get } from "svelte/store";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  isAdmin,
  isModerator,
  logout,
  me,
  refreshSession,
  session,
} from "./session";

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    headers: { "Content-Type": "application/json" },
  });
}

beforeEach(() => {
  session.set(null);
  me.set(undefined);
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("refreshSession", () => {
  it("loads the current user when the gateway session is authenticated", async () => {
    const fetchMock = vi.fn((input: URL) => {
      const path = input.pathname;
      if (path === "/auth/session") {
        return Promise.resolve(
          jsonResponse({ authenticated: true, username: "demo" }),
        );
      }
      if (path === "/api/v1/me") {
        return Promise.resolve(
          jsonResponse({
            username: "demo",
            roles: ["moderator"],
          }),
        );
      }
      return Promise.reject(new Error(`Unexpected ${path}`));
    });
    vi.stubGlobal("fetch", fetchMock);

    await expect(refreshSession()).resolves.toEqual({
      authenticated: true,
      username: "demo",
    });

    expect(get(session)).toEqual({ authenticated: true, username: "demo" });
    expect(get(me)).toEqual({ username: "demo", roles: ["moderator"] });
  });

  it("clears user state when the gateway session is anonymous", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse({ authenticated: false })),
    );

    await refreshSession();

    expect(get(session)).toEqual({ authenticated: false });
    expect(get(me)).toBeNull();
  });
});

describe("logout", () => {
  it("posts to the gateway and clears local session state", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);
    session.set({ authenticated: true, username: "demo" });
    me.set({ username: "demo", roles: ["admin"] });

    await logout();

    const [url, init] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect(url.toString()).toBe("http://localhost:3000/auth/logout");
    expect(init.method).toBe("POST");
    expect(get(session)).toEqual({ authenticated: false });
    expect(get(me)).toBeNull();
  });
});

describe("role helpers", () => {
  it("treats admins as moderators through the Stackverse role hierarchy", () => {
    expect(isModerator({ username: "mod", roles: ["moderator"] })).toBe(true);
    expect(isModerator({ username: "admin", roles: ["admin"] })).toBe(true);
    expect(isModerator({ username: "demo", roles: [] })).toBe(false);
    expect(isModerator(null)).toBe(false);
  });

  it("keeps admin checks strict", () => {
    expect(isAdmin({ username: "admin", roles: ["admin"] })).toBe(true);
    expect(isAdmin({ username: "mod", roles: ["moderator"] })).toBe(false);
    expect(isAdmin(undefined)).toBe(false);
  });
});
