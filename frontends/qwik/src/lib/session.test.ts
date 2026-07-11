import { afterEach, describe, expect, it, vi } from "vitest";
import { isAdmin, isModerator, logout, refreshSession } from "./session";

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    headers: { "Content-Type": "application/json", ...init.headers },
    ...init,
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("refreshSession", () => {
  it("keeps anonymous browsing independent from the identity endpoint", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ authenticated: false }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(refreshSession()).resolves.toEqual({
      session: { authenticated: false },
      me: null,
    });
    expect(fetchMock).toHaveBeenCalledTimes(1);
    expect((fetchMock.mock.calls[0][0] as URL).pathname).toBe("/auth/session");
  });

  it("loads backend identity only after the gateway confirms authentication", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ authenticated: true, username: "moderator" }),
      )
      .mockResolvedValueOnce(
        jsonResponse({ username: "moderator", roles: ["moderator"] }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(refreshSession()).resolves.toEqual({
      session: { authenticated: true, username: "moderator" },
      me: { username: "moderator", roles: ["moderator"] },
    });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect((fetchMock.mock.calls[1][0] as URL).pathname).toBe("/api/v1/me");
  });

  it("preserves the authenticated session when the backend identity call fails", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValueOnce(
        jsonResponse({ authenticated: true, username: "demo" }),
      )
      .mockResolvedValueOnce(
        jsonResponse({ title: "Unauthorized", status: 401 }, { status: 401 }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(refreshSession()).resolves.toEqual({
      session: { authenticated: true, username: "demo" },
      me: null,
    });
  });

  it.each([
    [
      "gateway error",
      () => Promise.resolve(new Response(null, { status: 503 })),
    ],
    ["network failure", () => Promise.reject(new Error("offline"))],
  ])("degrades to anonymous on %s", async (_label, response) => {
    vi.stubGlobal("fetch", vi.fn().mockImplementation(response));

    await expect(refreshSession()).resolves.toEqual({
      session: { authenticated: false },
      me: null,
    });
  });
});

describe("session actions and role boundaries", () => {
  it("makes logout best effort at the gateway boundary", async () => {
    const fetchMock = vi.fn().mockRejectedValue(new Error("offline"));
    vi.stubGlobal("fetch", fetchMock);

    await expect(logout()).resolves.toBeUndefined();
    const [url, init] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect(url.pathname).toBe("/auth/logout");
    expect(init.method).toBe("POST");
  });

  it("treats admin as hierarchical moderator while keeping admin-only checks strict", () => {
    const regular = { username: "demo", roles: [] };
    const moderator = { username: "moderator", roles: ["moderator"] };
    const admin = { username: "admin", roles: ["moderator", "admin"] };

    expect(isModerator(regular)).toBe(false);
    expect(isModerator(moderator)).toBe(true);
    expect(isModerator(admin)).toBe(true);
    expect(isModerator(null)).toBe(false);
    expect(isAdmin(moderator)).toBe(false);
    expect(isAdmin(admin)).toBe(true);
    expect(isAdmin(undefined)).toBe(false);
  });
});
