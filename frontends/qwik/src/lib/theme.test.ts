import { beforeEach, describe, expect, it } from "vitest";
import { applyTheme, readStoredTheme, THEME_STORAGE_KEY } from "./theme";

beforeEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute("data-theme");
});

describe("readStoredTheme", () => {
  it("returns auto when no supported explicit theme is stored", () => {
    expect(readStoredTheme()).toBe("auto");

    localStorage.setItem(THEME_STORAGE_KEY, "sepia");

    expect(readStoredTheme()).toBe("auto");
  });

  it("returns stored light and dark choices", () => {
    localStorage.setItem(THEME_STORAGE_KEY, "light");
    expect(readStoredTheme()).toBe("light");

    localStorage.setItem(THEME_STORAGE_KEY, "dark");
    expect(readStoredTheme()).toBe("dark");
  });
});

describe("applyTheme", () => {
  it("sets explicit themes on the root element and persists them", () => {
    applyTheme("dark");

    expect(document.documentElement.dataset.theme).toBe("dark");
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBe("dark");
  });

  it("removes explicit theme state for auto mode", () => {
    applyTheme("light");

    applyTheme("auto");

    expect(document.documentElement.hasAttribute("data-theme")).toBe(false);
    expect(localStorage.getItem(THEME_STORAGE_KEY)).toBeNull();
  });
});
