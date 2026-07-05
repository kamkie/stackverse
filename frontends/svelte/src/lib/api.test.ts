import { describe, expect, it } from "vitest";
import { queryString } from "./api";
import { endOfDayIso } from "./format";

describe("queryString", () => {
  it("skips empty values and repeats array params", () => {
    expect(queryString({ q: "text", tag: ["a", "b"], empty: "", nil: null })).toBe(
      "?q=text&tag=a&tag=b",
    );
  });
});

describe("endOfDayIso", () => {
  it("maps a local calendar day to the inclusive final microsecond", () => {
    expect(endOfDayIso("2026-07-05")).toMatch(/T.*:59\.999999Z$/);
  });
});
