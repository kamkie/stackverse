import { beforeEach, describe, expect, it, vi } from "vitest";

const db = vi.hoisted(() => ({
  query: vi.fn(),
}));

vi.mock("./db.js", () => ({ pool: db }));

import { DEFAULT_LANGUAGE, localize, messageBundle, parseAcceptLanguage, resolveLanguage } from "./i18n.js";

beforeEach(() => {
  db.query.mockReset();
});

const supportedLanguages = (...languages: string[]) => {
  db.query.mockResolvedValueOnce({ rows: languages.map((language) => ({ language })) });
};

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
  it("uses an explicitly requested language when it is supported", async () => {
    supportedLanguages("en", "pl");

    await expect(resolveLanguage("pl", "en;q=1")).resolves.toBe("pl");
  });

  it("falls through unsupported explicit languages to quality-ordered Accept-Language", async () => {
    supportedLanguages("en", "pl");

    await expect(resolveLanguage("de", "fr;q=1, pl;q=0.6, en;q=0.4")).resolves.toBe("pl");
  });

  it("uses English when neither explicit nor header languages are supported", async () => {
    supportedLanguages("pl");

    await expect(resolveLanguage("de", "fr;q=1")).resolves.toBe(DEFAULT_LANGUAGE);
  });
});

describe("localized messages (SPEC rules 9 + 11)", () => {
  it("localizes a key from the requested language with English as the SQL fallback", async () => {
    db.query.mockResolvedValueOnce({ rows: [{ text: "Nieprawidlowy URL" }] });

    await expect(localize("validation.url.invalid", "pl")).resolves.toBe("Nieprawidlowy URL");
    expect(db.query).toHaveBeenCalledWith(expect.stringContaining("select text from messages"), [
      "validation.url.invalid",
      ["pl", "en"],
      "pl",
    ]);
  });

  it("falls back to the message key when no localized text exists", async () => {
    db.query.mockResolvedValueOnce({ rows: [] });

    await expect(localize("validation.missing", "pl")).resolves.toBe("validation.missing");
  });

  it("builds a flat bundle where requested-language rows override English fallback rows", async () => {
    db.query.mockResolvedValueOnce({
      rows: [
        { key: "fallback.only", language: "en", text: "English fallback" },
        { key: "local.only", language: "pl", text: "Tylko po polsku" },
        { key: "shared", language: "pl", text: "Polski tekst" },
        { key: "shared", language: "en", text: "English text" },
      ],
    });

    await expect(messageBundle("pl")).resolves.toEqual({
      "fallback.only": "English fallback",
      "local.only": "Tylko po polsku",
      shared: "Polski tekst",
    });
    expect(db.query).toHaveBeenCalledWith(expect.stringContaining("where language = any($1::text[])"), [["pl", "en"]]);
  });
});
