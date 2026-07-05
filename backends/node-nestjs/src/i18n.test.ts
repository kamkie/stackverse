import { beforeEach, describe, expect, it, vi } from "vitest";

const queryMock = vi.hoisted(() => vi.fn());

vi.mock("./db.js", () => ({
  pool: { query: queryMock },
}));

import { localize, messageBundle, parseAcceptLanguage, resolveLanguage } from "./i18n.js";

beforeEach(() => {
  queryMock.mockReset();
});

describe("Accept-Language parsing (SPEC rule 8)", () => {
  it("orders by quality, listing order breaking ties", () => {
    expect(parseAcceptLanguage("en;q=0.5, zz, pl;q=0.8")).toEqual(["zz", "pl", "en"]);
  });

  it("reduces region subtags to the primary language", () => {
    expect(parseAcceptLanguage("pl-PL, en-US;q=0.7")).toEqual(["pl", "en"]);
  });

  it("skips unparseable entries instead of erroring", () => {
    expect(parseAcceptLanguage("***, pl")).toEqual(["pl"]);
    expect(parseAcceptLanguage(undefined)).toEqual([]);
    expect(parseAcceptLanguage("")).toEqual([]);
  });
});

describe("language resolution (SPEC rule 8)", () => {
  it("uses an explicit supported language before Accept-Language", async () => {
    queryMock.mockResolvedValueOnce({ rows: [{ language: "en" }, { language: "pl" }] });

    await expect(resolveLanguage("pl", "en;q=1")).resolves.toBe("pl");
  });

  it("falls through unsupported explicit language to the best supported Accept-Language entry", async () => {
    queryMock.mockResolvedValueOnce({ rows: [{ language: "en" }, { language: "pl" }] });

    await expect(resolveLanguage("de", "fr;q=0.9, pl-PL;q=0.8, en;q=0.1")).resolves.toBe("pl");
  });

  it("falls back to en when no requested language is supported", async () => {
    queryMock.mockResolvedValueOnce({ rows: [{ language: "pl" }] });

    await expect(resolveLanguage("de", "fr")).resolves.toBe("en");
  });
});

describe("localized messages (SPEC rules 9 + 11)", () => {
  it("returns localized text, then en fallback, then the key itself", async () => {
    queryMock.mockResolvedValueOnce({ rows: [{ text: "Wymagane" }] });
    await expect(localize("validation.required", "pl")).resolves.toBe("Wymagane");

    queryMock.mockResolvedValueOnce({ rows: [{ text: "Required" }] });
    await expect(localize("validation.required", "de")).resolves.toBe("Required");

    queryMock.mockResolvedValueOnce({ rows: [] });
    await expect(localize("validation.missing", "pl")).resolves.toBe("validation.missing");
  });

  it("builds bundles with requested-language values overriding en and en filling gaps", async () => {
    queryMock.mockResolvedValueOnce({
      rows: [
        { key: "ui.cancel", language: "pl", text: "Anuluj" },
        { key: "ui.cancel", language: "en", text: "Cancel" },
        { key: "ui.save", language: "en", text: "Save" },
        { key: "ui.save", language: "pl", text: "Zapisz" },
        { key: "ui.title", language: "en", text: "Bookmarks" },
      ],
    });

    await expect(messageBundle("pl")).resolves.toEqual({
      "ui.cancel": "Anuluj",
      "ui.save": "Zapisz",
      "ui.title": "Bookmarks",
    });
  });
});
