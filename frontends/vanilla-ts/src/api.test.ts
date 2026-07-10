import {
  ApiError,
  apiGet,
  apiSend,
  buildUrl,
  fetchSession,
  fieldErrorFor,
  messageOf,
} from "./api";
import type { Problem } from "./types";

beforeEach(() => {
  vi.spyOn(console, "debug").mockImplementation(() => undefined);
});

afterEach(() => {
  vi.restoreAllMocks();
  document.cookie = "XSRF-TOKEN=; Max-Age=0";
});

describe("buildUrl", () => {
  it("serializes repeatable query parameters and skips blanks", () => {
    const url = new URL(
      buildUrl("/api/v2/bookmarks", {
        tag: ["dev", "http"],
        q: "etag",
        cursor: "",
        page: 0,
      }),
    );

    expect(url.pathname).toBe("/api/v2/bookmarks");
    expect(url.searchParams.getAll("tag")).toEqual(["dev", "http"]);
    expect(url.searchParams.get("q")).toBe("etag");
    expect(url.searchParams.get("page")).toBe("0");
    expect(url.searchParams.has("cursor")).toBe(false);
  });
});

describe("fieldErrorFor", () => {
  it("matches exact and indexed field paths", () => {
    const problem: Problem = {
      errors: [
        {
          field: "url",
          messageKey: "validation.url.required",
          message: "URL is required.",
        },
        {
          field: "tags[0]",
          messageKey: "validation.tag.invalid",
          message: "Bad tag.",
        },
      ],
    };
    const error = new ApiError(400, problem);

    expect(fieldErrorFor(error, "url")?.messageKey).toBe(
      "validation.url.required",
    );
    expect(fieldErrorFor(error, "tags")?.messageKey).toBe(
      "validation.tag.invalid",
    );
    expect(fieldErrorFor(error, "title")).toBeUndefined();
  });

  it("matches nested field paths and ignores non-API errors", () => {
    const error = new ApiError(400, {
      errors: [
        {
          field: "owner.email",
          messageKey: "validation.email.invalid",
          message: "Invalid email.",
        },
      ],
    });

    expect(fieldErrorFor(error, "owner")?.message).toBe("Invalid email.");
    expect(fieldErrorFor(new Error("plain"), "owner")).toBeUndefined();
  });
});

describe("ApiError", () => {
  it("prefers problem detail and falls back to the status text", () => {
    expect(
      new ApiError(409, { title: "Conflict", detail: "Already reported." })
        .message,
    ).toBe("Already reported.");
    expect(new ApiError(404, { title: "Missing" }).message).toBe("Missing");
    expect(new ApiError(503).message).toBe("HTTP 503");
  });

  it("exposes field errors as an empty list when the problem omits them", () => {
    expect(new ApiError(400, { title: "Bad request" }).fieldErrors).toEqual([]);
  });
});

describe("messageOf", () => {
  it("returns Error messages and stringifies non-Error values", () => {
    expect(messageOf(new Error("network failed"))).toBe("network failed");
    expect(messageOf(42)).toBe("42");
  });
});

describe("apiGet", () => {
  it("sends same-origin JSON requests with credentials and query parameters", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ items: [] }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    await expect(
      apiGet<{ items: unknown[] }>("/api/v2/bookmarks", { tag: ["dev"] }),
    ).resolves.toEqual({ items: [] });

    const [url, init] = fetchMock.mock.calls[0]!;
    expect(new URL(String(url)).searchParams.getAll("tag")).toEqual(["dev"]);
    expect(init).toMatchObject({
      credentials: "include",
      headers: { Accept: "application/json" },
    });
  });

  it("throws ApiError with a problem document for JSON failures", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          title: "Validation failed",
          detail: "URL is required.",
          errors: [
            {
              field: "url",
              messageKey: "validation.url.required",
              message: "URL is required.",
            },
          ],
        }),
        {
          status: 400,
          headers: { "Content-Type": "application/problem+json" },
        },
      ),
    );

    await expect(apiGet("/api/v1/bookmarks")).rejects.toMatchObject({
      name: "ApiError",
      status: 400,
      message: "URL is required.",
      fieldErrors: [
        {
          field: "url",
          messageKey: "validation.url.required",
          message: "URL is required.",
        },
      ],
    });
  });
});

describe("apiSend", () => {
  it("sends JSON bodies with the current CSRF token", async () => {
    document.cookie = "XSRF-TOKEN=csrf-one";
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ id: "bookmark-1" }), {
        status: 201,
        headers: { "Content-Type": "application/json" },
      }),
    );

    await expect(
      apiSend<{ id: string }>("POST", "/api/v1/bookmarks", {
        url: "https://example.com",
        title: "Example",
      }),
    ).resolves.toEqual({ id: "bookmark-1" });

    const [, init] = fetchMock.mock.calls[0]!;
    expect(init).toMatchObject({
      method: "POST",
      credentials: "include",
      headers: {
        Accept: "application/json",
        "Content-Type": "application/json",
        "X-XSRF-TOKEN": "csrf-one",
      },
      body: JSON.stringify({ url: "https://example.com", title: "Example" }),
    });
  });

  it("retries one 403 after rereading the CSRF cookie", async () => {
    document.cookie = "XSRF-TOKEN=stale";
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async () => {
        if (fetchMock.mock.calls.length === 1) {
          document.cookie = "XSRF-TOKEN=fresh";
          return new Response(JSON.stringify({ title: "Forbidden" }), {
            status: 403,
            headers: { "Content-Type": "application/problem+json" },
          });
        }
        return new Response(JSON.stringify({ ok: true }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        });
      });

    await expect(
      apiSend<{ ok: boolean }>("PUT", "/api/v1/bookmarks/one", {}),
    ).resolves.toEqual({
      ok: true,
    });

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(fetchMock.mock.calls[0]![1]?.headers).toMatchObject({
      "X-XSRF-TOKEN": "stale",
    });
    expect(fetchMock.mock.calls[1]![1]?.headers).toMatchObject({
      "X-XSRF-TOKEN": "fresh",
    });
  });

  it("returns undefined for no-content mutations without adding a JSON body", async () => {
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValue(new Response(null, { status: 204 }));

    await expect(
      apiSend<void>("DELETE", "/api/v1/bookmarks/one"),
    ).resolves.toBeUndefined();

    const [, init] = fetchMock.mock.calls[0]!;
    expect(init).toMatchObject({
      method: "DELETE",
      headers: { Accept: "application/json" },
    });
    expect(init).not.toHaveProperty("body");
    expect(init?.headers).not.toHaveProperty("Content-Type");
  });
});

describe("fetchSession", () => {
  it("uses the shared response parsing for gateway session reads", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(JSON.stringify({ authenticated: true, username: "demo" }), {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    await expect(fetchSession("/auth/session")).resolves.toEqual({
      authenticated: true,
      username: "demo",
    });
  });
});
