import { beforeEach, describe, expect, it, vi } from "vitest";
import type { User } from "../types";

function stubFetch(handler: (request: Request) => Response | Promise<Response>) {
  const requests: Request[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      requests.push(request);
      return handler(request);
    }),
  );
  return requests;
}

const demoUser: User = { username: "demo", roles: [] };
const adminUser: User = { username: "admin", roles: ["admin"] };

describe("auth session state", () => {
  beforeEach(() => {
    vi.resetModules();
  });

  it("loads authenticated sessions and then loads the current user", async () => {
    const requests = stubFetch((request) => {
      const path = new URL(request.url).pathname;
      if (path === "/auth/session") {
        return Response.json({ authenticated: true, username: "admin" });
      }
      if (path === "/api/v1/me") {
        return Response.json(adminUser);
      }
      return new Response(null, { status: 404 });
    });
    const { loadSession, me, session } = await import("../auth");

    await loadSession();

    expect(requests.map((request) => new URL(request.url).pathname)).toEqual([
      "/auth/session",
      "/api/v1/me",
    ]);
    expect(session.value).toEqual({ authenticated: true, username: "admin" });
    expect(me.value).toEqual(adminUser);
  });

  it("treats a failed session probe as logged out and clears stale identity", async () => {
    stubFetch(() => new Response(null, { status: 503 }));
    const { loadSession, me, session } = await import("../auth");
    session.value = { authenticated: true, username: "demo" };
    me.value = demoUser;

    await loadSession();

    expect(session.value).toEqual({ authenticated: false });
    expect(me.value).toBeNull();
  });

  it("clears the session when /me returns unauthorized", async () => {
    stubFetch((request) => {
      if (new URL(request.url).pathname === "/api/v1/me") {
        return Response.json(
          { title: "Unauthorized", status: 401, detail: "No session" },
          { status: 401 },
        );
      }
      return new Response(null, { status: 404 });
    });
    const { loadMe, me, session } = await import("../auth");
    session.value = { authenticated: true, username: "demo" };
    me.value = demoUser;

    await loadMe();

    expect(session.value).toEqual({ authenticated: false });
    expect(me.value).toBeNull();
  });

  it("posts logout and clears local auth state", async () => {
    const requests = stubFetch((request) =>
      new URL(request.url).pathname === "/auth/logout"
        ? new Response(null, { status: 204 })
        : new Response(null, { status: 404 }),
    );
    const { logout, me, session } = await import("../auth");
    session.value = { authenticated: true, username: "demo" };
    me.value = demoUser;

    await logout();

    expect(requests).toHaveLength(1);
    expect(requests[0]?.method).toBe("POST");
    expect(new URL(requests[0]?.url ?? "").pathname).toBe("/auth/logout");
    expect(session.value).toEqual({ authenticated: false });
    expect(me.value).toBeNull();
  });
});
