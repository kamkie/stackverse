import {
  useCallback,
  useEffect,
  useMemo,
  useState,
  type ReactNode,
} from "react";
import type { components } from "../api/schema";
import { I18nContext, type I18nContextValue } from "./I18nContext";

type MessageBundle = components["schemas"]["MessageBundle"];

interface CachedBundle {
  etag: string | null;
  bundle: MessageBundle;
}

const LANG_STORAGE_KEY = "stackverse.lang";
const bundleStorageKey = (lang: string | null) =>
  `stackverse.bundle.${lang ?? "auto"}`;

function readCachedBundle(lang: string | null): CachedBundle | null {
  try {
    const raw = localStorage.getItem(bundleStorageKey(lang));
    return raw ? (JSON.parse(raw) as CachedBundle) : null;
  } catch {
    return null;
  }
}

function writeCachedBundle(lang: string | null, cached: CachedBundle): void {
  try {
    localStorage.setItem(bundleStorageKey(lang), JSON.stringify(cached));
  } catch {
    // Cache is an optimization; a full bundle fetch still works without it.
  }
}

/**
 * Loads the message bundle for a language, revalidating a locally cached copy
 * with `If-None-Match` so an unchanged bundle costs a 304 with no body.
 */
async function fetchBundle(lang: string | null): Promise<CachedBundle> {
  const cached = readCachedBundle(lang);
  const headers = new Headers();
  if (cached?.etag) headers.set("If-None-Match", cached.etag);

  const url = new URL("/api/v1/messages/bundle", location.origin);
  if (lang !== null) url.searchParams.set("lang", lang);
  const response = await fetch(url, { headers });

  if (response.status === 304 && cached) return cached;
  if (!response.ok) throw new Error(`Failed to load message bundle: ${response.status}`);

  const bundle = (await response.json()) as MessageBundle;
  const fresh: CachedBundle = { etag: response.headers.get("ETag"), bundle };
  writeCachedBundle(lang, fresh);
  return fresh;
}

/**
 * Runtime i18n without an i18n library: every user-facing string comes from
 * `GET /api/v1/messages/bundle`. Keys the bundle does not (yet) contain render
 * as their last segment — messages are runtime-managed, so admins can supply
 * translations for them without a deploy.
 */
export function I18nProvider({ children }: { children: ReactNode }) {
  const [lang, setLangState] = useState<string | null>(() =>
    localStorage.getItem(LANG_STORAGE_KEY),
  );
  const [bundle, setBundle] = useState<MessageBundle | null>(null);
  const [reloadTick, setReloadTick] = useState(0);

  useEffect(() => {
    let cancelled = false;
    fetchBundle(lang)
      .then((cached) => {
        if (!cancelled) setBundle(cached.bundle);
      })
      .catch(() => {
        // Leave the previous bundle (or key fallbacks) in place.
        if (!cancelled) setBundle((prev) => prev ?? { language: "en", messages: {} });
      });
    return () => {
      cancelled = true;
    };
  }, [lang, reloadTick]);

  const t = useCallback(
    (key: string): string =>
      bundle?.messages[key] ?? key.slice(key.lastIndexOf(".") + 1),
    [bundle],
  );

  const tCount = useCallback(
    (key: string, count: number): string => {
      const category = new Intl.PluralRules(bundle?.language ?? "en").select(count);
      return (
        bundle?.messages[`${key}.${category}`] ??
        bundle?.messages[key] ??
        key.slice(key.lastIndexOf(".") + 1)
      );
    },
    [bundle],
  );

  const setLang = useCallback((next: string) => {
    localStorage.setItem(LANG_STORAGE_KEY, next);
    setLangState(next);
  }, []);

  const refresh = useCallback(() => setReloadTick((tick) => tick + 1), []);

  useEffect(() => {
    if (!bundle) return;
    document.documentElement.lang = bundle.language;
    document.title = bundle.messages["ui.app.title"] ?? document.title;
  }, [bundle]);

  const value = useMemo<I18nContextValue>(
    () => ({ lang, resolvedLanguage: bundle?.language ?? "en", setLang, t, tCount, refresh }),
    [lang, bundle, setLang, t, tCount, refresh],
  );

  // Hold rendering until the first bundle arrives so screens never flash keys.
  if (bundle === null) {
    return (
      <div className="sv-loading">
        <span className="sv-spinner" />
      </div>
    );
  }

  return <I18nContext.Provider value={value}>{children}</I18nContext.Provider>;
}
