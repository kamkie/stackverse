import {
  keyFallback,
  localizeFieldError,
  MessageBundleLoadError,
  readStoredLanguage,
  RuntimeI18n,
  storeLanguage,
} from "./i18n";

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason?: unknown) => void;
  const promise = new Promise<T>((complete, fail) => {
    resolve = complete;
    reject = fail;
  });
  return { promise, resolve, reject };
}

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

  it("falls back to a cached bundle when the transport is offline", async () => {
    localStorage.setItem(
      "stackverse.bundle.en",
      JSON.stringify({
        etag: 'W/"en"',
        bundle: {
          language: "en",
          messages: { "ui.app.title": "Offline Stackverse" },
        },
      }),
    );
    vi.spyOn(globalThis, "fetch").mockRejectedValue(
      new TypeError("network unavailable"),
    );

    const i18n = new RuntimeI18n();
    await expect(i18n.load("en")).resolves.toBeUndefined();

    expect(i18n.resolvedLanguage).toBe("en");
    expect(i18n.t("ui.app.title")).toBe("Offline Stackverse");
    expect(document.documentElement.lang).toBe("en");
    expect(document.title).toBe("Offline Stackverse");
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

  it("preserves the active and stored language when an uncached switch fails", async () => {
    localStorage.setItem("stackverse.lang", "en");
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            language: "en",
            messages: { "ui.app.title": "English Stackverse" },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      )
      .mockResolvedValueOnce(new Response(null, { status: 503 }));

    const i18n = new RuntimeI18n();
    await i18n.load();
    await expect(i18n.setLanguage("pl")).rejects.toThrow(
      "Failed to load message bundle: 503",
    );

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(i18n.lang).toBe("en");
    expect(i18n.resolvedLanguage).toBe("en");
    expect(i18n.t("ui.app.title")).toBe("English Stackverse");
    expect(readStoredLanguage()).toBe("en");
    expect(document.documentElement.lang).toBe("en");
    expect(document.title).toBe("English Stackverse");
  });

  it("lets a concurrent refresh complete the pending language selection", async () => {
    localStorage.setItem("stackverse.lang", "en");
    const switchResponse = deferred<Response>();
    const refreshResponse = deferred<Response>();
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementationOnce(async () => switchResponse.promise)
      .mockImplementationOnce(async () => refreshResponse.promise);

    const i18n = new RuntimeI18n();
    const languageSwitch = i18n.setLanguage("pl");
    const bundleRefresh = i18n.load();

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(
      new URL(String(fetchMock.mock.calls[0]![0])).searchParams.get("lang"),
    ).toBe("pl");
    expect(
      new URL(String(fetchMock.mock.calls[1]![0])).searchParams.get("lang"),
    ).toBe("pl");

    refreshResponse.resolve(
      new Response(
        JSON.stringify({
          language: "pl",
          messages: { "ui.app.title": "Wybrany Stackverse" },
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json", ETag: 'W/"pl-new"' },
        },
      ),
    );
    await bundleRefresh;

    switchResponse.resolve(
      new Response(
        JSON.stringify({
          language: "pl",
          messages: { "ui.app.title": "Stary Stackverse" },
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json", ETag: 'W/"pl-old"' },
        },
      ),
    );
    await languageSwitch;

    expect(i18n.lang).toBe("pl");
    expect(i18n.resolvedLanguage).toBe("pl");
    expect(i18n.t("ui.app.title")).toBe("Wybrany Stackverse");
    expect(readStoredLanguage()).toBe("pl");
    expect(document.documentElement.lang).toBe("pl");
    expect(document.title).toBe("Wybrany Stackverse");
    expect(localStorage.getItem("stackverse.bundle.pl")).toContain(
      "Wybrany Stackverse",
    );
    expect(localStorage.getItem("stackverse.bundle.pl")).not.toContain(
      "Stary Stackverse",
    );
  });

  it("clears an aborted pending selection without changing the committed language", async () => {
    localStorage.setItem("stackverse.lang", "en");
    const polish = deferred<Response>();
    const fetchMock = vi
      .spyOn(globalThis, "fetch")
      .mockImplementationOnce(async () => polish.promise)
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            language: "en",
            messages: { "ui.app.title": "English Stackverse" },
          }),
          { status: 200, headers: { "Content-Type": "application/json" } },
        ),
      );
    const controller = new AbortController();
    const i18n = new RuntimeI18n();

    const languageSwitch = i18n.setLanguage("pl", {
      signal: controller.signal,
    });
    controller.abort();
    polish.resolve(
      new Response(
        JSON.stringify({
          language: "pl",
          messages: { "ui.app.title": "Stale Stackverse" },
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    );
    await expect(languageSwitch).resolves.toBeUndefined();
    await i18n.load();

    expect(fetchMock).toHaveBeenCalledTimes(2);
    expect(
      new URL(String(fetchMock.mock.calls[1]![0])).searchParams.get("lang"),
    ).toBe("en");
    expect(i18n.lang).toBe("en");
    expect(i18n.resolvedLanguage).toBe("en");
    expect(readStoredLanguage()).toBe("en");
    expect(document.documentElement.lang).toBe("en");
    expect(document.title).toBe("English Stackverse");
    expect(localStorage.getItem("stackverse.bundle.pl")).toBeNull();
  });

  it("lets only the latest concurrent language request publish state", async () => {
    const polish = deferred<Response>();
    const english = deferred<Response>();
    vi.spyOn(globalThis, "fetch").mockImplementation(async (input) => {
      const lang = new URL(String(input)).searchParams.get("lang");
      if (lang === "pl") return polish.promise;
      if (lang === "en") return english.promise;
      throw new Error(`unexpected language: ${lang}`);
    });

    const i18n = new RuntimeI18n();
    const olderSwitch = i18n.setLanguage("pl");
    const newerSwitch = i18n.setLanguage("en");

    english.resolve(
      new Response(
        JSON.stringify({
          language: "en",
          messages: { "ui.app.title": "Latest Stackverse" },
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json", ETag: 'W/"en"' },
        },
      ),
    );
    await newerSwitch;

    polish.resolve(
      new Response(
        JSON.stringify({
          language: "pl",
          messages: { "ui.app.title": "Stale Stackverse" },
        }),
        {
          status: 200,
          headers: { "Content-Type": "application/json", ETag: 'W/"pl"' },
        },
      ),
    );
    await olderSwitch;

    expect(i18n.lang).toBe("en");
    expect(i18n.resolvedLanguage).toBe("en");
    expect(i18n.t("ui.app.title")).toBe("Latest Stackverse");
    expect(readStoredLanguage()).toBe("en");
    expect(document.documentElement.lang).toBe("en");
    expect(document.title).toBe("Latest Stackverse");
    expect(localStorage.getItem("stackverse.bundle.pl")).toBeNull();
    expect(localStorage.getItem("stackverse.bundle.en")).toContain(
      "Latest Stackverse",
    );
  });

  it("suppresses a stale decode failure after a newer request wins", async () => {
    const staleDecode = deferred<unknown>();
    const staleResponse = new Response(null, {
      status: 200,
      headers: { "Content-Type": "application/json", ETag: 'W/"pl"' },
    });
    const staleJson = vi
      .spyOn(staleResponse, "json")
      .mockReturnValue(staleDecode.promise);
    vi.spyOn(globalThis, "fetch")
      .mockResolvedValueOnce(staleResponse)
      .mockResolvedValueOnce(
        new Response(
          JSON.stringify({
            language: "en",
            messages: { "ui.app.title": "Winning Stackverse" },
          }),
          {
            status: 200,
            headers: { "Content-Type": "application/json", ETag: 'W/"en"' },
          },
        ),
      );

    const i18n = new RuntimeI18n();
    const staleSwitch = i18n.setLanguage("pl");
    await vi.waitFor(() => expect(staleJson).toHaveBeenCalledOnce());

    await i18n.setLanguage("en");
    staleDecode.reject(new SyntaxError("stale malformed response"));
    await expect(staleSwitch).resolves.toBeUndefined();

    expect(i18n.lang).toBe("en");
    expect(i18n.resolvedLanguage).toBe("en");
    expect(i18n.t("ui.app.title")).toBe("Winning Stackverse");
    expect(readStoredLanguage()).toBe("en");
    expect(document.documentElement.lang).toBe("en");
    expect(document.title).toBe("Winning Stackverse");
    expect(localStorage.getItem("stackverse.bundle.pl")).toBeNull();
    expect(localStorage.getItem("stackverse.bundle.en")).toContain(
      "Winning Stackverse",
    );
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
