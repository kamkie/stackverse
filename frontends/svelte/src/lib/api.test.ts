import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { ApiError, api, fieldErrorFor, jsonBody, queryString } from "./api";
import { endOfDayIso } from "./format";

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    headers: { "Content-Type": "application/json", ...init.headers },
    ...init,
  });
}

beforeEach(() => {
  document.cookie = "XSRF-TOKEN=; Max-Age=0; path=/";
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("queryString", () => {
  it("skips empty values and repeats array params", () => {
    expect(
      queryString({ q: "text", tag: ["a", "b"], empty: "", nil: null }),
    ).toBe("?q=text&tag=a&tag=b");
  });

  it("serializes falsy-but-meaningful values", () => {
    expect(queryString({ page: 0, enabled: false, size: 20 })).toBe(
      "?page=0&enabled=false&size=20",
    );
  });
});

describe("api", () => {
  it("adds JSON and CSRF headers for mutating API requests", async () => {
    document.cookie = "XSRF-TOKEN=csrf-token; path=/";
    const fetchMock = vi.fn().mockResolvedValue(jsonResponse({ ok: true }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(
      api<{ ok: boolean }>("/api/v1/bookmarks", {
        method: "post",
        ...jsonBody({ title: "Svelte", url: "https://svelte.dev" }),
      }),
    ).resolves.toEqual({ ok: true });

    const [url, init] = fetchMock.mock.calls[0] as [URL, RequestInit];
    const headers = init.headers as Headers;
    expect(url.toString()).toBe("http://localhost:3000/api/v1/bookmarks");
    expect(init.method).toBe("POST");
    expect(headers.get("content-type")).toBe("application/json");
    expect(headers.get("X-XSRF-TOKEN")).toBe("csrf-token");
    expect(init.body).toBe(
      JSON.stringify({ title: "Svelte", url: "https://svelte.dev" }),
    );
  });

  it("does not add a CSRF header outside the API proxy", async () => {
    document.cookie = "XSRF-TOKEN=csrf-token; path=/";
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 204 }));
    vi.stubGlobal("fetch", fetchMock);

    await api<void>("/auth/logout", { method: "POST" });

    const [, init] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect((init.headers as Headers).has("X-XSRF-TOKEN")).toBe(false);
  });

  it("returns undefined for empty successful responses", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 304 })),
    );

    await expect(api<void>("/api/v1/messages/bundle")).resolves.toBeUndefined();
  });

  it("throws ApiError with parsed problem details", async () => {
    const problem = {
      title: "Validation failed",
      status: 400,
      errors: [
        {
          field: "title",
          messageKey: "validation.title.required",
          message: "Title is required",
        },
      ],
    };
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(problem, { status: 400 })),
    );

    await expect(api("/api/v1/bookmarks")).rejects.toMatchObject({
      status: 400,
      problem,
      message: "Validation failed",
    });
  });
});

describe("fieldErrorFor", () => {
  it("returns the localized field message from an ApiError", () => {
    const error = new ApiError(400, {
      errors: [
        {
          field: "url",
          messageKey: "validation.url.invalid",
          message: "Enter a valid URL",
        },
      ],
    });

    expect(fieldErrorFor(error, "url")).toBe("Enter a valid URL");
    expect(fieldErrorFor(error, "title")).toBeUndefined();
    expect(fieldErrorFor(new Error("nope"), "url")).toBeUndefined();
  });
});

describe("endOfDayIso", () => {
  it("maps a local calendar day to the inclusive final microsecond", () => {
    expect(endOfDayIso("2026-07-05")).toMatch(/T.*:59\.999999Z$/);
  });
});
