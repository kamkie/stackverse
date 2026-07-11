// CSRF double-submit: state-changing /api calls must echo the gateway's
// XSRF-TOKEN cookie as an X-XSRF-TOKEN header (frontends/README.md). Real
// gateways enforce this with a 403; MSW does not, so the suite pins it here.
import { describe, expect, it, vi } from "vitest";
import { api } from "../api/client";

function stubFetch(): Request[] {
  const requests: Request[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL) => {
      const request = input instanceof Request ? input : new Request(input);
      requests.push(request);
      if (request.method === "GET") {
        return Response.json({ tags: [] });
      }
      return Response.json(
        {
          id: "00000000-0000-4000-8000-000000000001",
          owner: "demo",
          url: "https://example.com",
          title: "t",
          tags: [],
          visibility: "private",
          status: "active",
          createdAt: new Date(0).toISOString(),
          updatedAt: new Date(0).toISOString(),
        },
        { status: 201 },
      );
    }),
  );
  return requests;
}

describe("csrf double-submit", () => {
  it("echoes the XSRF-TOKEN cookie as X-XSRF-TOKEN on state-changing calls", async () => {
    document.cookie = "XSRF-TOKEN=abc123";
    const requests = stubFetch();

    const result = await api.POST("/api/v1/bookmarks", {
      body: {
        url: "https://example.com",
        title: "t",
        tags: [],
        visibility: "private",
      },
    });

    expect(result.response.status).toBe(201);
    expect(requests).toHaveLength(1);
    expect(requests[0]?.headers.get("X-XSRF-TOKEN")).toBe("abc123");
  });

  it("sends no CSRF header on reads or without a cookie (mock mode)", async () => {
    const requests = stubFetch();

    await api.GET("/api/v1/tags");
    await api.POST("/api/v1/bookmarks", {
      body: {
        url: "https://example.com",
        title: "t",
        tags: [],
        visibility: "private",
      },
    });

    expect(requests).toHaveLength(2);
    expect(requests[0]?.headers.get("X-XSRF-TOKEN")).toBeNull();
    expect(requests[1]?.headers.get("X-XSRF-TOKEN")).toBeNull();
  });

  it("retries a rejected state-changing request when the gateway refreshes the token", async () => {
    document.cookie = "XSRF-TOKEN=old";
    const requests: Request[] = [];
    vi.stubGlobal(
      "fetch",
      vi.fn(async (input: RequestInfo | URL) => {
        const request = input instanceof Request ? input : new Request(input);
        requests.push(request);
        if (requests.length === 1) {
          document.cookie = "XSRF-TOKEN=fresh";
          return Response.json({ title: "Forbidden", status: 403 }, { status: 403 });
        }
        return Response.json(
          {
            id: "00000000-0000-4000-8000-000000000001",
            owner: "demo",
            url: "https://example.com",
            title: "t",
            tags: [],
            visibility: "private",
            status: "active",
            createdAt: new Date(0).toISOString(),
            updatedAt: new Date(0).toISOString(),
          },
          { status: 201 },
        );
      }),
    );

    const result = await api.POST("/api/v1/bookmarks", {
      body: {
        url: "https://example.com",
        title: "t",
        tags: [],
        visibility: "private",
      },
    });

    expect(result.response.status).toBe(201);
    expect(requests).toHaveLength(2);
    expect(requests[0]?.headers.get("X-XSRF-TOKEN")).toBe("old");
    expect(requests[1]?.headers.get("X-XSRF-TOKEN")).toBe("fresh");
  });
});
