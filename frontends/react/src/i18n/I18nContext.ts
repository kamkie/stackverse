import { createContext, useContext } from "react";

export interface I18nContextValue {
  /** The user's stored language choice; null = follow Accept-Language. */
  lang: string | null;
  /** The language the server actually resolved (bundle.language). */
  resolvedLanguage: string;
  setLang: (lang: string) => void;
  /** Message for a key; falls back to the key's last segment. */
  t: (key: string) => string;
  /**
   * Pluralized message: resolves `<key>.<plural category>` (CLDR category for
   * `count` in the resolved language), falling back to the bare key and then
   * to the key's last segment — the same fallback rule as `t`.
   */
  tCount: (key: string, count: number) => string;
  /** Revalidates the bundle (cheap 304 when unchanged) — call after message writes. */
  refresh: () => void;
}

export const I18nContext = createContext<I18nContextValue | null>(null);

export function useI18n(): I18nContextValue {
  const ctx = useContext(I18nContext);
  if (!ctx) throw new Error("useI18n must be used inside <I18nProvider>");
  return ctx;
}
