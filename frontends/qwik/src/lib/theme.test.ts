import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import { applyTheme, readStoredTheme, THEME_STORAGE_KEY } from "./theme";

beforeEach(() => {
  localStorage.clear();
  document.documentElement.removeAttribute("data-theme");
});

afterEach(() => {
  vi.restoreAllMocks();
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

  it("falls back to auto when storage is unavailable", () => {
    vi.spyOn(Storage.prototype, "getItem").mockImplementation(() => {
      throw new Error("storage denied");
    });

    expect(readStoredTheme()).toBe("auto");
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
