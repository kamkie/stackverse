import { keyFallback, localizeFieldError, RuntimeI18n } from "./i18n";

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

  it("loads bundles with ETag revalidation", async () => {
    const responses = [
      new Response(JSON.stringify({ language: "en", messages: { "ui.app.title": "Stackverse" } }), {
        status: 200,
        headers: { "Content-Type": "application/json", ETag: 'W/"one"' },
      }),
      new Response(null, { status: 304 }),
    ];
    vi.spyOn(globalThis, "fetch").mockImplementation(async () => {
      const next = responses.shift();
      if (!next) throw new Error("unexpected fetch");
      return next;
    });

    const i18n = new RuntimeI18n();
    await i18n.load("en");
    await i18n.load("en");

    expect(i18n.t("ui.app.title")).toBe("Stackverse");
    expect(globalThis.fetch).toHaveBeenCalledTimes(2);
  });
});

