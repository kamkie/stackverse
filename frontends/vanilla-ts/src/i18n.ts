import type { FieldError, MessageBundle } from "./types";
import { fetchWithNetworkError } from "./api";

const LANG_STORAGE_KEY = "stackverse.lang";
const bundleStorageKey = (lang: string | null) =>
  `stackverse.bundle.${lang ?? "auto"}`;

interface CachedBundle {
  etag: string | null;
  bundle: MessageBundle;
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
    // Bundle caching is an optimization.
  }
}

export function readStoredLanguage(): string | null {
  try {
    return localStorage.getItem(LANG_STORAGE_KEY);
  } catch {
    return null;
  }
}

export function storeLanguage(lang: string): void {
  try {
    localStorage.setItem(LANG_STORAGE_KEY, lang);
  } catch {
    // The language still applies for this session.
  }
}

export function keyFallback(key: string): string {
  return key.slice(key.lastIndexOf(".") + 1);
}

export class RuntimeI18n {
  private bundle: MessageBundle | null = null;

  lang: string | null = readStoredLanguage();

  get resolvedLanguage(): string {
    return this.bundle?.language ?? "en";
  }

  private applyDocumentBundle(bundle: MessageBundle): void {
    document.documentElement.lang = bundle.language;
    document.title = bundle.messages["ui.app.title"] ?? document.title;
  }

  async load(lang: string | null = this.lang): Promise<void> {
    const cached = readCachedBundle(lang);
    const headers = new Headers();
    if (cached?.etag) headers.set("If-None-Match", cached.etag);

    const url = new URL("/api/v1/messages/bundle", window.location.origin);
    if (lang !== null) url.searchParams.set("lang", lang);
    const response = await fetchWithNetworkError(url, {
      headers,
      credentials: "include",
    });

    if (response.status === 304 && cached) {
      this.bundle = cached.bundle;
      this.applyDocumentBundle(cached.bundle);
      return;
    }
    if (!response.ok) {
      if (cached) {
        this.bundle = cached.bundle;
        this.applyDocumentBundle(cached.bundle);
        return;
      }
      throw new Error(`Failed to load message bundle: ${response.status}`);
    }

    const bundle = (await response.json()) as MessageBundle;
    const fresh = { etag: response.headers.get("ETag"), bundle };
    writeCachedBundle(lang, fresh);
    this.bundle = bundle;
    this.applyDocumentBundle(bundle);
  }

  async setLanguage(lang: string): Promise<void> {
    this.lang = lang;
    storeLanguage(lang);
    await this.load(lang);
  }

  t = (key: string): string => {
    return this.bundle?.messages[key] ?? keyFallback(key);
  };

  tCount = (key: string, count: number): string => {
    const category = new Intl.PluralRules(this.resolvedLanguage).select(count);
    const pluralKey = `${key}.${category}`;
    const plural = this.t(pluralKey);
    return plural !== keyFallback(pluralKey) ? plural : this.t(key);
  };
}

export function localizeFieldError(
  error: FieldError,
  t: (key: string) => string,
): string {
  const localized = t(error.messageKey);
  return localized === keyFallback(error.messageKey)
    ? error.message
    : localized;
}
