import type { FieldError, MessageBundle } from "./types";
import { ApiNetworkError, fetchWithNetworkError } from "./api";

const LANG_STORAGE_KEY = "stackverse.lang";
const bundleStorageKey = (lang: string | null) =>
  `stackverse.bundle.${lang ?? "auto"}`;

interface CachedBundle {
  etag: string | null;
  bundle: MessageBundle;
}

interface LoadOptions {
  signal?: AbortSignal;
}

export class MessageBundleLoadError extends Error {
  constructor(message: string, options?: ErrorOptions) {
    super(message, options);
    this.name = "MessageBundleLoadError";
  }
}

function isMessageBundle(value: unknown): value is MessageBundle {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Record<string, unknown>;
  if (
    typeof candidate.language !== "string" ||
    typeof candidate.messages !== "object" ||
    candidate.messages === null ||
    Array.isArray(candidate.messages)
  ) {
    return false;
  }
  return Object.values(candidate.messages).every(
    (message) => typeof message === "string",
  );
}

function isCachedBundle(value: unknown): value is CachedBundle {
  if (typeof value !== "object" || value === null) return false;
  const candidate = value as Record<string, unknown>;
  return (
    (candidate.etag === null || typeof candidate.etag === "string") &&
    isMessageBundle(candidate.bundle)
  );
}

function readCachedBundle(lang: string | null): CachedBundle | null {
  try {
    const raw = localStorage.getItem(bundleStorageKey(lang));
    if (!raw) return null;
    const cached: unknown = JSON.parse(raw);
    return isCachedBundle(cached) ? cached : null;
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
  private latestRequest = 0;
  private pendingLanguage: string | undefined;

  lang: string | null = readStoredLanguage();

  get resolvedLanguage(): string {
    return this.bundle?.language ?? "en";
  }

  private applyDocumentBundle(bundle: MessageBundle): void {
    document.documentElement.lang = bundle.language;
    document.title = bundle.messages["ui.app.title"] ?? document.title;
  }

  private clearPendingLanguage(
    request: number,
    lang: string | null,
    publishLanguage: boolean,
  ): void {
    if (
      request === this.latestRequest &&
      publishLanguage &&
      this.pendingLanguage === lang
    ) {
      this.pendingLanguage = undefined;
    }
  }

  private shouldIgnore(
    request: number,
    lang: string | null,
    publishLanguage: boolean,
    signal?: AbortSignal,
  ): boolean {
    if (request !== this.latestRequest) return true;
    if (!signal?.aborted) return false;
    this.clearPendingLanguage(request, lang, publishLanguage);
    return true;
  }

  private applyBundle(
    request: number,
    lang: string | null,
    bundle: MessageBundle,
    fresh: CachedBundle | null,
    publishLanguage: boolean,
    signal?: AbortSignal,
  ): void {
    if (this.shouldIgnore(request, lang, publishLanguage, signal)) return;

    if (fresh) writeCachedBundle(lang, fresh);
    this.bundle = bundle;
    this.applyDocumentBundle(bundle);
    if (publishLanguage && lang !== null && this.pendingLanguage === lang) {
      this.lang = lang;
      storeLanguage(lang);
      this.pendingLanguage = undefined;
    }
  }

  private async requestBundle(
    lang: string | null,
    publishLanguage: boolean,
    options: LoadOptions,
  ): Promise<void> {
    if (options.signal?.aborted) return;
    const request = ++this.latestRequest;
    if (this.pendingLanguage !== undefined && this.pendingLanguage !== lang) {
      this.pendingLanguage = undefined;
    }
    const cached = readCachedBundle(lang);
    const headers = new Headers();
    if (cached?.etag) headers.set("If-None-Match", cached.etag);

    const url = new URL("/api/v1/messages/bundle", window.location.origin);
    if (lang !== null) url.searchParams.set("lang", lang);
    let response: Response;
    try {
      response = await fetchWithNetworkError(url, {
        headers,
        credentials: "include",
        ...(options.signal ? { signal: options.signal } : {}),
      });
    } catch (error) {
      if (this.shouldIgnore(request, lang, publishLanguage, options.signal))
        return;
      if (error instanceof ApiNetworkError && cached) {
        this.applyBundle(
          request,
          lang,
          cached.bundle,
          null,
          publishLanguage,
          options.signal,
        );
        return;
      }
      this.clearPendingLanguage(request, lang, publishLanguage);
      throw error;
    }

    if (this.shouldIgnore(request, lang, publishLanguage, options.signal))
      return;

    if (response.status === 304 && cached) {
      this.applyBundle(
        request,
        lang,
        cached.bundle,
        null,
        publishLanguage,
        options.signal,
      );
      return;
    }
    if (!response.ok) {
      if (cached) {
        this.applyBundle(
          request,
          lang,
          cached.bundle,
          null,
          publishLanguage,
          options.signal,
        );
        return;
      }
      this.clearPendingLanguage(request, lang, publishLanguage);
      throw new MessageBundleLoadError(
        `Failed to load message bundle: ${response.status}`,
      );
    }

    let bundle: MessageBundle;
    try {
      const body: unknown = await response.json();
      if (!isMessageBundle(body)) {
        throw new MessageBundleLoadError("Invalid message bundle response");
      }
      bundle = body;
    } catch (error) {
      if (this.shouldIgnore(request, lang, publishLanguage, options.signal))
        return;
      this.clearPendingLanguage(request, lang, publishLanguage);
      if (error instanceof MessageBundleLoadError) throw error;
      throw new MessageBundleLoadError("Invalid message bundle response", {
        cause: error,
      });
    }
    if (this.shouldIgnore(request, lang, publishLanguage, options.signal))
      return;
    const fresh = { etag: response.headers.get("ETag"), bundle };
    this.applyBundle(
      request,
      lang,
      bundle,
      fresh,
      publishLanguage,
      options.signal,
    );
  }

  async load(
    lang: string | null | undefined = undefined,
    options: LoadOptions = {},
  ): Promise<void> {
    const targetLanguage =
      lang === undefined ? (this.pendingLanguage ?? this.lang) : lang;
    const publishLanguage = this.pendingLanguage === targetLanguage;
    await this.requestBundle(targetLanguage, publishLanguage, options);
  }

  async setLanguage(lang: string, options: LoadOptions = {}): Promise<void> {
    if (options.signal?.aborted) return;
    this.pendingLanguage = lang;
    await this.requestBundle(lang, true, options);
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
