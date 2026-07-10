import { beforeEach, describe, expect, it } from "vitest";
import {
  addReportedId,
  applyTheme,
  escapeHtml,
  readReportedIds,
  readStoredTheme,
  removeReportedId,
  selected,
} from "./view-helpers";

describe("view helpers", () => {
  beforeEach(() => {
    localStorage.clear();
    sessionStorage.clear();
    document.documentElement.removeAttribute("data-theme");
  });

  it("escapes template values and selects exact matches", () => {
    expect(escapeHtml(`<a title="x">Tom & 'Ada'</a>`)).toBe(
      "&lt;a title=&quot;x&quot;&gt;Tom &amp; &#39;Ada&#39;&lt;/a&gt;",
    );
    expect(selected("open", "open")).toBe(" selected");
    expect(selected("open", "closed")).toBe("");
  });

  it("persists explicit themes and clears automatic mode", () => {
    applyTheme("dark");
    expect(readStoredTheme()).toBe("dark");
    expect(document.documentElement.dataset.theme).toBe("dark");

    applyTheme("auto");
    expect(readStoredTheme()).toBe("auto");
    expect(document.documentElement.hasAttribute("data-theme")).toBe(false);
  });

  it("tracks reported bookmarks in session storage", () => {
    addReportedId("bookmark-1");
    expect(readReportedIds()).toEqual(new Set(["bookmark-1"]));

    removeReportedId("bookmark-1");
    expect(readReportedIds()).toEqual(new Set());
  });
});
