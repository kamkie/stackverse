import { describe, expect, it } from "vitest";
import { parseAcceptLanguage } from "./i18n.js";

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
