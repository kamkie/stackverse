import {
  keyFallback,
  localizeFieldError,
  MessageBundleLoadError,
  readStoredLanguage,
  RuntimeI18n,
  storeLanguage,
} from "./i18n";

afterEach(() => {
  vi.restoreAllMocks();
  localStorage.clear();
  document.documentElement.removeAttribute("lang");
  document.title = "";
});

describe("i18n helpers", () => {
  it("falls back to the last key segment", () => {
    expect(keyFallback("ui.action.save")).toBe("save");
  });

  it("uses the server field message when the bundle lacks the key", () => {
    const message = localizeFieldError(
      {
        field: "url",
        messageKey: "validation.url.required",
        message: "URL is required.",
      },
      (key) => keyFallback(key),
    );

    expect(message).toBe("URL is required.");
  });

  it("uses localized validation text when the bundle provides it", () => {
    const message = localizeFieldError(
      {
        field: "title",
        messageKey: "validation.title.required",
        message: "Title is required.",
      },
      (key) =>
        key === "validation.title.required" ? "Podaj tytul." : keyFallback(key),
    );

    expect(message).toBe("Podaj tytul.");
  });

  it("loads bundles with ETag revalidation", async () => {
    const responses = [
      new Response(
        JSON.stringify({
          language: "en",
          messages: { "ui.app.title": "Stackverse" },
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json", ETag: 'W/"one"' },
        },
      ),
      new Response(null, { status: 304 }),
    ];
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementation(async () => {
        const next = responses.shift();
        if (!next) throw new Error("unexpected fetch");
        return next;
      });

    const i18n = new RuntimeI18n();
    await i18n.load("en");
    await i18n.load("en");

    expect(i18n.t("ui.app.title")).toBe("Stackverse");
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const secondRequest = fetchMock.mock.calls[1]!;
    expect(new URL(String(secondRequest[0])).searchParams.get("lang")).toBe(
      "en",
    );
    expect(secondRequest[1]?.headers).toBeInstanceOf(Headers);
    expect((secondRequest[1]?.headers as Headers).get("If-None-Match")).toBe(
      'W/"one"',
    );
    expect(document.documentElement.lang).toBe("en");
    expect(document.title).toBe("Stackverse");
  });

  it("syncs document metadata when the server revalidates a cached bundle", async () => {
    localStorage.setItem(
      "stackverse.bundle.en",
      JSON.stringify({
        etag: 'W/"en"',
        bundle: {
          language: "en",
          messages: { "ui.app.title": "Cached Stackverse" },
        },
      }),
    );
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(null, { status: 304 }),
    );

    await new RuntimeI18n().load("en");

    expect(document.documentElement.lang).toBe("en");
    expect(document.title).toBe("Cached Stackverse");
  });

  it("falls back to a cached bundle when refresh fails", async () => {
    localStorage.setItem(
      "stackverse.bundle.pl",
      JSON.stringify({
        etag: 'W/"pl"',
        bundle: {
          language: "pl",
          messages: { "ui.app.title": "Stackverse PL" },
        },
      }),
    );
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(null, { status: 503 }),
    );

    const i18n = new RuntimeI18n();
    await expect(i18n.load("pl")).resolves.toBeUndefined();

    expect(i18n.resolvedLanguage).toBe("pl");
    expect(i18n.t("ui.app.title")).toBe("Stackverse PL");
    expect(document.documentElement.lang).toBe("pl");
    expect(document.title).toBe("Stackverse PL");
  });

  it("throws when the bundle request fails without a cached copy", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(null, { status: 503 }),
    );

    await expect(new RuntimeI18n().load("pl")).rejects.toEqual(
      expect.objectContaining({
        name: "MessageBundleLoadError",
        message: "Failed to load message bundle: 503",
      }),
    );
  });

  it("types malformed bundle responses as operational load failures", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response("{", {
        status: 200,
        headers: { "Content-Type": "application/json" },
      }),
    );

    await expect(new RuntimeI18n().load("en")).rejects.toBeInstanceOf(
      MessageBundleLoadError,
    );
  });

  it("does not apply or cache a bundle after its load is aborted", async () => {
    let resolveResponse!: (response: Response) => void;
    const response = new Promise<Response>((resolve) => {
      resolveResponse = resolve;
    });
    vi.spyOn(globalThis, "fetch").mockReturnValue(response);
    document.documentElement.lang = "pl";
    document.title = "Current title";
    const controller = new AbortController();
    const i18n = new RuntimeI18n();

    const load = i18n.load("en", { signal: controller.signal });
    controller.abort();
    resolveResponse(
      new Response(
        JSON.stringify({
          language: "en",
          messages: { "ui.app.title": "Stale title" },
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json" },
        },
      ),
    );
    await expect(load).resolves.toBeUndefined();

    expect(i18n.t("ui.app.title")).toBe("title");
    expect(document.documentElement.lang).toBe("pl");
    expect(document.title).toBe("Current title");
    expect(localStorage.getItem("stackverse.bundle.en")).toBeNull();
  });

  it("stores the selected language and reloads that language", async () => {
    const fetchMock = vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          language: "pl",
          messages: { "ui.app.title": "Stackverse" },
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json" },
        },
      ),
    );

    const i18n = new RuntimeI18n();
    await i18n.setLanguage("pl");

    expect(i18n.lang).toBe("pl");
    expect(readStoredLanguage()).toBe("pl");
    expect(
      new URL(String(fetchMock.mock.calls[0]![0])).searchParams.get("lang"),
    ).toBe("pl");
  });

  it("keeps language storage failures from blocking selection", () => {
    const setItem = vi
      .spyOn(Storage.prototype, "setItem")
      .mockImplementation(() => {
        throw new Error("quota exceeded");
      });

    expect(() => storeLanguage("pl")).not.toThrow();
    expect(setItem).toHaveBeenCalledWith("stackverse.lang", "pl");
  });

  it("selects plural messages and falls back to the base key when plural forms are missing", async () => {
    vi.spyOn(globalThis, "fetch").mockResolvedValue(
      new Response(
        JSON.stringify({
          language: "en",
          messages: {
            "ui.count.bookmarks.one": "One bookmark",
            "ui.count.bookmarks.other": "Many bookmarks",
            "ui.count.reports": "Reports",
          },
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    );

    const i18n = new RuntimeI18n();
    await i18n.load("en");

    expect(i18n.tCount("ui.count.bookmarks", 1)).toBe("One bookmark");
    expect(i18n.tCount("ui.count.bookmarks", 2)).toBe("Many bookmarks");
    expect(i18n.tCount("ui.count.reports", 2)).toBe("Reports");
  });
});
