// CSRF double-submit: state-changing /api calls must echo the gateway's
// XSRF-TOKEN cookie as an X-XSRF-TOKEN header (frontends/README.md). Real
// gateways enforce this with a 403; MSW does not, so the suite pins it here.
import { afterEach, describe, expect, it } from "vitest";
import { api } from "../api/client";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { server } from "./setup";

function captureRequests(): Request[] {
  const requests: Request[] = [];
  server.events.on("request:start", ({ request }) => {
    requests.push(request);
  });
  return requests;
}

afterEach(() => {
  server.events.removeAllListeners();
  document.cookie = "XSRF-TOKEN=; expires=Thu, 01 Jan 1970 00:00:00 GMT; path=/";
});

describe("csrf double-submit", () => {
  it("echoes the XSRF-TOKEN cookie as X-XSRF-TOKEN on state-changing calls", async () => {
    setCurrentUser(MOCK_USERS.demo);
    document.cookie = "XSRF-TOKEN=abc123";
    const requests = captureRequests();

    const result = await api.POST("/api/v1/bookmarks", {
      body: { url: "https://example.com", title: "t", tags: [], visibility: "private" },
    });

    expect(result.response.status).toBe(201);
    expect(requests).toHaveLength(1);
    expect(requests[0]?.headers.get("X-XSRF-TOKEN")).toBe("abc123");
  });

  it("sends no CSRF header on reads or without a cookie (mock mode)", async () => {
    setCurrentUser(MOCK_USERS.demo);
    const requests = captureRequests();

    await api.GET("/api/v1/tags");
    await api.POST("/api/v1/bookmarks", {
      body: { url: "https://example.com", title: "t", tags: [], visibility: "private" },
    });

    expect(requests).toHaveLength(2);
    expect(requests[0]?.headers.get("X-XSRF-TOKEN")).toBeNull();
    expect(requests[1]?.headers.get("X-XSRF-TOKEN")).toBeNull();
  });
});
