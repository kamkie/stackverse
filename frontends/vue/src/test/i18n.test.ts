import { beforeEach, describe, expect, it, vi } from "vitest";

function stubFetch(responseFor: (request: Request) => Response | Promise<Response>) {
  const requests: Request[] = [];
  vi.stubGlobal(
    "fetch",
    vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
      const request = input instanceof Request ? input : new Request(input, init);
      requests.push(request);
      return responseFor(request);
    }),
  );
  return requests;
}

describe("i18n bundle loading", () => {
  beforeEach(() => {
    vi.resetModules();
    document.title = "Stackverse";
    document.documentElement.lang = "";
  });

  it("loads and caches a fresh bundle for the automatic language", async () => {
    const requests = stubFetch((request) => {
      expect(new URL(request.url).search).toBe("");
      expect(request.headers.get("If-None-Match")).toBeNull();
      return Response.json(
        {
          language: "en",
          messages: {
            "ui.app.title": "Stackverse Bookmarks",
            "ui.save": "Save",
            "ui.bookmarks.count.one": "1 bookmark",
            "ui.bookmarks.count.other": "bookmarks",
          },
        },
        { headers: { ETag: '"bundle-v1"' } },
      );
    });
    const { bundle, loadBundle, t, tCount } = await import("../i18n/i18n");

    await loadBundle();

    expect(requests).toHaveLength(1);
    expect(bundle.value?.language).toBe("en");
    expect(document.documentElement.lang).toBe("en");
    expect(document.title).toBe("Stackverse Bookmarks");
    expect(t("ui.save")).toBe("Save");
    expect(t("ui.missing.key")).toBe("key");
    expect(tCount("ui.bookmarks.count", 1)).toBe("1 bookmark");
    expect(tCount("ui.bookmarks.count", 2)).toBe("bookmarks");
    expect(JSON.parse(localStorage.getItem("stackverse.bundle.auto") ?? "{}")).toMatchObject({
      etag: '"bundle-v1"',
      bundle: { language: "en" },
    });
  });

  it("reuses cached bundles after a 304 response", async () => {
    localStorage.setItem("stackverse.lang", "pl");
    localStorage.setItem(
      "stackverse.bundle.pl",
      JSON.stringify({
        etag: '"bundle-pl"',
        bundle: {
          language: "pl",
          messages: { "ui.app.title": "Stackverse PL", "ui.save": "Zapisz" },
        },
      }),
    );
    const requests = stubFetch((request) => {
      const url = new URL(request.url);
      expect(url.searchParams.get("lang")).toBe("pl");
      expect(request.headers.get("If-None-Match")).toBe('"bundle-pl"');
      return new Response(null, { status: 304 });
    });
    const { bundle, loadBundle, selectedLanguage, t } = await import("../i18n/i18n");

    await loadBundle();

    expect(requests).toHaveLength(1);
    expect(selectedLanguage.value).toBe("pl");
    expect(bundle.value?.language).toBe("pl");
    expect(document.documentElement.lang).toBe("pl");
    expect(document.title).toBe("Stackverse PL");
    expect(t("ui.save")).toBe("Zapisz");
  });

  it("falls back to an empty English bundle when loading fails", async () => {
    stubFetch(() => new Response(null, { status: 503 }));
    const { bundle, loadBundle, t } = await import("../i18n/i18n");

    await loadBundle();

    expect(bundle.value).toEqual({ language: "en", messages: {} });
    expect(document.documentElement.lang).toBe("en");
    expect(t("ui.admin.messages")).toBe("messages");
  });

  it("persists language switches and reloads the requested bundle", async () => {
    const requests = stubFetch((request) => {
      expect(new URL(request.url).searchParams.get("lang")).toBe("pl");
      return Response.json({
        language: "pl",
        messages: { "ui.app.title": "Stackverse PL" },
      });
    });
    const { selectedLanguage, setLanguage } = await import("../i18n/i18n");

    await setLanguage("pl");

    expect(requests).toHaveLength(1);
    expect(selectedLanguage.value).toBe("pl");
    expect(localStorage.getItem("stackverse.lang")).toBe("pl");
    expect(document.documentElement.lang).toBe("pl");
  });
});
