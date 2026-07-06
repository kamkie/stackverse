import { afterEach, describe, expect, it, vi } from "vitest";
import { loadBookmarkCursor } from "./bookmarkCursor";
import type { Bookmark } from "./types";

function bookmark(id: string): Bookmark {
  return {
    id,
    owner: "demo",
    url: `https://example.com/${id}`,
    title: id,
    notes: "",
    tags: [],
    visibility: "public",
    status: "active",
    createdAt: "2026-07-05T12:00:00Z",
    updatedAt: "2026-07-05T12:00:00Z",
  };
}

function jsonResponse(body: unknown): Response {
  return new Response(JSON.stringify(body), {
    headers: { "Content-Type": "application/json" },
  });
}

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("loadBookmarkCursor", () => {
  it("resets the list and omits the previous cursor for a fresh load", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(jsonResponse({ items: [bookmark("new")], nextCursor: "next" }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await loadBookmarkCursor({
      reset: true,
      current: [bookmark("old")],
      nextCursor: "previous",
      params: { visibility: "public", tag: ["svelte"] },
    });

    expect(result).toEqual({
      bookmarks: [bookmark("new")],
      nextCursor: "next",
    });
    expect((fetchMock.mock.calls[0][0] as URL).toString()).toBe(
      "http://localhost:3000/api/v2/bookmarks?size=20&visibility=public&tag=svelte",
    );
  });

  it("appends the next page when continuing a cursor", async () => {
    const existing = bookmark("existing");
    const next = bookmark("next");
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ items: [next] }));
    vi.stubGlobal("fetch", fetchMock);

    const result = await loadBookmarkCursor({
      reset: false,
      current: [existing],
      nextCursor: "opaque-cursor",
      params: { q: "docs" },
    });

    expect(result).toEqual({
      bookmarks: [existing, next],
      nextCursor: undefined,
    });
    expect((fetchMock.mock.calls[0][0] as URL).toString()).toBe(
      "http://localhost:3000/api/v2/bookmarks?size=20&cursor=opaque-cursor&q=docs",
    );
  });
});
