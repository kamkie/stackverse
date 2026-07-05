export const THEME_STORAGE_KEY = "stackverse.theme";
export const THEME_OPTIONS = ["auto", "light", "dark"] as const;
export type ThemeOption = (typeof THEME_OPTIONS)[number];

export function readStoredTheme(): ThemeOption {
  try {
    const stored = localStorage.getItem(THEME_STORAGE_KEY);
    return stored === "light" || stored === "dark" ? stored : "auto";
  } catch {
    return "auto";
  }
}

export function applyTheme(next: ThemeOption): void {
  const root = document.documentElement;
  if (next === "auto") root.removeAttribute("data-theme");
  else root.setAttribute("data-theme", next);
  try {
    if (next === "auto") localStorage.removeItem(THEME_STORAGE_KEY);
    else localStorage.setItem(THEME_STORAGE_KEY, next);
  } catch {
    // Storage unavailable: the choice just won't survive a reload.
  }
}
