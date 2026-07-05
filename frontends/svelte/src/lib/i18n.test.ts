import { get } from "svelte/store";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { i18n, loadBundle, m, mc, setLanguage, type I18nState } from "./i18n";
import type { MessageBundle } from "./types";

function bundle(language: string, messages: Record<string, string>): MessageBundle {
  return { language, messages };
}

function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    headers: { "Content-Type": "application/json", ...init.headers },
    ...init,
  });
}

beforeEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute("lang");
  document.title = "Stackverse";
  i18n.set({
    lang: null,
    resolvedLanguage: "en",
    messages: {},
    ready: false,
  });
});

afterEach(() => {
  vi.unstubAllGlobals();
});

describe("loadBundle", () => {
  it("loads and caches a requested language bundle", async () => {
    const responseBundle = bundle("pl", {
      "ui.app.title": "Stackverse PL",
      "ui.nav.feed": "Publiczne",
    });
    const fetchMock = vi.fn().mockResolvedValue(
      jsonResponse(responseBundle, {
        headers: { ETag: '"pl-v1"' },
      }),
    );
    vi.stubGlobal("fetch", fetchMock);

    await loadBundle("pl");

    const [url, init] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect(url.toString()).toBe(
      "http://localhost:3000/api/v1/messages/bundle?lang=pl",
    );
    expect((init.headers as Headers).has("If-None-Match")).toBe(false);
    expect(get(i18n)).toEqual({
      lang: "pl",
      resolvedLanguage: "pl",
      messages: responseBundle.messages,
      ready: true,
    });
    expect(document.documentElement.lang).toBe("pl");
    expect(document.title).toBe("Stackverse PL");
    expect(JSON.parse(localStorage.getItem("stackverse.bundle.pl") ?? "{}")).toEqual({
      etag: '"pl-v1"',
      bundle: responseBundle,
    });
  });

  it("reuses a cached bundle when the server returns 304", async () => {
    const cached = {
      etag: '"en-v1"',
      bundle: bundle("en", { "ui.app.title": "Stackverse Cached" }),
    };
    localStorage.setItem("stackverse.bundle.auto", JSON.stringify(cached));
    const fetchMock = vi.fn().mockResolvedValue(new Response(null, { status: 304 }));
    vi.stubGlobal("fetch", fetchMock);

    await loadBundle(null);

    const [url, init] = fetchMock.mock.calls[0] as [URL, RequestInit];
    expect(url.toString()).toBe("http://localhost:3000/api/v1/messages/bundle");
    expect((init.headers as Headers).get("If-None-Match")).toBe('"en-v1"');
    expect(get(i18n).messages).toEqual(cached.bundle.messages);
    expect(document.title).toBe("Stackverse Cached");
  });

  it("marks the store ready when the bundle request fails", async () => {
    vi.stubGlobal("fetch", vi.fn().mockRejectedValue(new Error("offline")));

    await loadBundle("pl");

    expect(get(i18n)).toEqual({
      lang: "pl",
      resolvedLanguage: "en",
      messages: {},
      ready: true,
    });
  });
});

describe("setLanguage", () => {
  it("persists the selected language before loading it", async () => {
    vi.stubGlobal(
      "fetch",
      vi.fn().mockResolvedValue(jsonResponse(bundle("pl", { "ui.app.title": "Tytul" }))),
    );

    await setLanguage("pl");

    expect(localStorage.getItem("stackverse.lang")).toBe("pl");
    expect(get(i18n).resolvedLanguage).toBe("pl");
  });
});

describe("message lookup", () => {
  const state: I18nState = {
    lang: "en",
    resolvedLanguage: "en",
    ready: true,
    messages: {
      "ui.items.one": "1 item",
      "ui.items.other": "many items",
      "ui.generic": "Generic",
    },
  };

  it("falls back to the final key segment for missing messages", () => {
    expect(m(state, "ui.nav.feed")).toBe("feed");
  });

  it("uses CLDR plural categories and then the bare key fallback", () => {
    expect(mc(state, "ui.items", 1)).toBe("1 item");
    expect(mc(state, "ui.items", 2)).toBe("many items");
    expect(mc(state, "ui.generic", 3)).toBe("Generic");
    expect(mc(state, "ui.unknown", 3)).toBe("unknown");
  });
});
