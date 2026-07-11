import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import {
  initialI18nState,
  loadBundle,
  m,
  mc,
  readStoredLanguage,
  setLanguage,
  type I18nState,
} from "./i18n";

function bundleResponse(
  language: string,
  messages: Record<string, string>,
  etag = '"bundle-v1"',
): Response {
  return new Response(JSON.stringify({ language, messages }), {
    headers: {
      "Content-Type": "application/json",
      ETag: etag,
    },
  });
}

beforeEach(() => {
  localStorage.clear();
  document.documentElement.lang = "";
  document.title = "Original title";
});

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe("message bundle loading", () => {
  it("loads, applies, and caches an explicitly selected language", async () => {
    const fetchMock = vi
      .fn()
      .mockResolvedValue(
        bundleResponse("pl", { "ui.app.title": "Zakładki", greeting: "Cześć" }),
      );
    vi.stubGlobal("fetch", fetchMock);

    await expect(loadBundle("pl")).resolves.toEqual({
      lang: "pl",
      resolvedLanguage: "pl",
      messages: { "ui.app.title": "Zakładki", greeting: "Cześć" },
      ready: true,
    });

    const [url, init] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect(url.toString()).toBe(
      "http://localhost:3000/api/v1/messages/bundle?lang=pl",
    );
    expect((init.headers as Headers).has("If-None-Match")).toBe(false);
    expect(document.documentElement.lang).toBe("pl");
    expect(document.title).toBe("Zakładki");
    expect(
      JSON.parse(localStorage.getItem("stackverse.bundle.pl") ?? ""),
    ).toEqual({
      etag: '"bundle-v1"',
      bundle: {
        language: "pl",
        messages: { "ui.app.title": "Zakładki", greeting: "Cześć" },
      },
    });
  });

  it("revalidates and reuses a cached automatic-language bundle on 304", async () => {
    const cached = {
      etag: '"cached"',
      bundle: {
        language: "en",
        messages: { "ui.app.title": "Stackverse", greeting: "Hello" },
      },
    };
    localStorage.setItem("stackverse.bundle.auto", JSON.stringify(cached));
    const fetchMock = vi
      .fn()
      .mockResolvedValue(new Response(null, { status: 304 }));
    vi.stubGlobal("fetch", fetchMock);

    await expect(loadBundle(null)).resolves.toEqual({
      lang: null,
      resolvedLanguage: "en",
      messages: cached.bundle.messages,
      ready: true,
    });

    const [url, init] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect(url.toString()).toBe("http://localhost:3000/api/v1/messages/bundle");
    expect((init.headers as Headers).get("If-None-Match")).toBe('"cached"');
  });

  it("recovers from an invalid cache entry with a fresh response", async () => {
    localStorage.setItem("stackverse.bundle.en", "not-json");
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(bundleResponse("en", { greeting: "Hello" })),
    );

    await expect(loadBundle("en")).resolves.toMatchObject({
      lang: "en",
      resolvedLanguage: "en",
      messages: { greeting: "Hello" },
      ready: true,
    });
  });

  it("becomes ready with contract fallbacks when the bundle endpoint fails", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(new Response(null, { status: 503 })),
    );

    await expect(loadBundle("pl")).resolves.toEqual({
      ...initialI18nState,
      lang: "pl",
      ready: true,
    });
    expect(document.documentElement.lang).toBe("");
    expect(document.title).toBe("Original title");
  });
});

describe("language persistence and lookup", () => {
  it("persists a language choice before loading its bundle", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(bundleResponse("pl", { greeting: "Cześć" })),
    );

    const state = await setLanguage("pl");

    expect(localStorage.getItem("stackverse.lang")).toBe("pl");
    expect(readStoredLanguage()).toBe("pl");
    expect(state.resolvedLanguage).toBe("pl");
  });

  it("continues when browser storage is unavailable", async () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("storage denied");
    });
    vi.spyOn(Storage.prototype, "setItem").mockImplementation(() => {
      throw new Error("storage denied");
    });
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(bundleResponse("en", { greeting: "Hello" })),
    );

    expect(readStoredLanguage()).toBeNull();
    await expect(setLanguage("en")).resolves.toMatchObject({
      lang: "en",
      resolvedLanguage: "en",
      ready: true,
    });
  });

  it("uses localized strings, plural categories, and readable key fallbacks", () => {
    const state: I18nState = {
      lang: "en",
      resolvedLanguage: "en",
      messages: {
        greeting: "Hello",
        "items.one": "one item",
        "items.other": "many items",
        notifications: "notifications",
      },
      ready: true,
    };

    expect(m(state, "greeting")).toBe("Hello");
    expect(m(state, "ui.action.save")).toBe("save");
    expect(mc(state, "items", 1)).toBe("one item");
    expect(mc(state, "items", 2)).toBe("many items");
    expect(mc(state, "notifications", 3)).toBe("notifications");
    expect(mc(state, "ui.report.count", 3)).toBe("count");
  });
});
