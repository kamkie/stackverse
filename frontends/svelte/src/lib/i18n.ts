import { get, writable } from "svelte/store";
import type { MessageBundle } from "./types";

interface CachedBundle {
  etag: string | null;
  bundle: MessageBundle;
}

export interface I18nState {
  lang: string | null;
  resolvedLanguage: string;
  messages: Record<string, string>;
  ready: boolean;
}

const LANG_STORAGE_KEY = "stackverse.lang";
const bundleStorageKey = (lang: string | null) =>
  `stackverse.bundle.${lang ?? "auto"}`;

function readStoredLanguage(): string | null {
  try {
    return localStorage.getItem(LANG_STORAGE_KEY);
  } catch {
    return null;
  }
}

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

export const i18n = writable<I18nState>({
  lang: null,
  resolvedLanguage: "en",
  messages: {},
  ready: false,
});

export const SUPPORTED_LANGUAGES = ["en", "pl"] as const;

export async function loadBundle(lang = readStoredLanguage()): Promise<void> {
  try {
    const cached = await fetchBundle(lang);
    i18n.set({
      lang,
      resolvedLanguage: cached.bundle.language,
      messages: cached.bundle.messages,
      ready: true,
    });
    document.documentElement.lang = cached.bundle.language;
    document.title = cached.bundle.messages["ui.app.title"] ?? document.title;
  } catch {
    i18n.update((state) => ({
      ...state,
      lang,
      ready: true,
    }));
  }
}

export async function setLanguage(lang: string): Promise<void> {
  try {
    localStorage.setItem(LANG_STORAGE_KEY, lang);
  } catch {
    // Storage unavailable: the choice just won't survive a reload.
  }
  await loadBundle(lang);
}

export async function refreshBundle(): Promise<void> {
  await loadBundle(get(i18n).lang);
}

export function m(state: I18nState, key: string): string {
  return state.messages[key] ?? key.slice(key.lastIndexOf(".") + 1);
}

export function mc(state: I18nState, key: string, count: number): string {
  const category = new Intl.PluralRules(state.resolvedLanguage).select(count);
  return (
    state.messages[`${key}.${category}`] ??
    state.messages[key] ??
    key.slice(key.lastIndexOf(".") + 1)
  );
}
