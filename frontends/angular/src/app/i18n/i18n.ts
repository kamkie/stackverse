import { computed, effect, Injectable, signal } from '@angular/core';
import type { MessageBundle } from '../api/types';

interface CachedBundle {
  etag: string | null;
  bundle: MessageBundle;
}

const LANG_STORAGE_KEY = 'stackverse.lang';
const BUNDLE_FETCH_TIMEOUT_MS = 10_000;
const bundleStorageKey = (lang: string | null) => `stackverse.bundle.${lang ?? 'auto'}`;

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
 * with `If-None-Match` so an unchanged bundle costs a 304 with no body. Plain
 * fetch, not HttpClient — the ETag handshake needs the raw response and none
 * of the app's interceptors apply to this read.
 */
async function fetchBundle(lang: string | null, signal: AbortSignal): Promise<CachedBundle> {
  const cached = readCachedBundle(lang);
  const headers = new Headers();
  if (cached?.etag) headers.set('If-None-Match', cached.etag);

  const url = new URL('/api/v1/messages/bundle', location.origin);
  if (lang !== null) url.searchParams.set('lang', lang);
  const response = await fetch(url, { headers, signal });

  if (response.status === 304 && cached) return cached;
  if (!response.ok) throw new Error(`Failed to load message bundle: ${response.status}`);

  const bundle = (await response.json()) as MessageBundle;
  const fresh: CachedBundle = { etag: response.headers.get('ETag'), bundle };
  writeCachedBundle(lang, fresh);
  return fresh;
}

/**
 * Runtime i18n without an i18n library: every user-facing string comes from
 * `GET /api/v1/messages/bundle`. Keys the bundle does not (yet) contain render
 * as their last segment — messages are runtime-managed, so admins can supply
 * translations for them without a deploy. `t`/`tCount` read the bundle
 * signal, so templates re-render when the bundle changes.
 */
@Injectable({ providedIn: 'root' })
export class I18n {
  /** The user's stored language choice; null = follow Accept-Language. */
  private readonly langState = signal<string | null>(localStorage.getItem(LANG_STORAGE_KEY));
  private readonly bundle = signal<MessageBundle | null>(null);
  private readonly reloadTick = signal(0);
  private generation = 0;

  readonly lang = this.langState.asReadonly();
  /** False until the first bundle arrives — screens must never flash keys. */
  readonly ready = computed(() => this.bundle() !== null);
  /** The language the server actually resolved (bundle.language). */
  readonly resolvedLanguage = computed(() => this.bundle()?.language ?? 'en');

  constructor() {
    effect((onCleanup) => {
      const lang = this.langState();
      this.reloadTick();
      const generation = ++this.generation;
      const controller = new AbortController();
      let cancelled = false;
      const timeoutId = window.setTimeout(() => controller.abort(), BUNDLE_FETCH_TIMEOUT_MS);
      onCleanup(() => {
        cancelled = true;
        window.clearTimeout(timeoutId);
        controller.abort();
      });

      fetchBundle(lang, controller.signal)
        .then((cached) => {
          if (generation === this.generation) this.bundle.set(cached.bundle);
        })
        .catch(() => {
          // Leave the previous bundle (or key fallbacks) in place.
          if (!cancelled && generation === this.generation) {
            this.bundle.update((prev) => prev ?? { language: 'en', messages: {} });
          }
        })
        .finally(() => {
          window.clearTimeout(timeoutId);
        });
    });

    effect(() => {
      const bundle = this.bundle();
      if (!bundle) return;
      document.documentElement.lang = bundle.language;
      document.title = bundle.messages['ui.app.title'] ?? document.title;
    });
  }

  /** Message for a key; falls back to the key's last segment. */
  readonly t = (key: string): string =>
    this.bundle()?.messages[key] ?? key.slice(key.lastIndexOf('.') + 1);

  /**
   * Pluralized message: resolves `<key>.<plural category>` (CLDR category for
   * `count` in the resolved language), falling back to the bare key and then
   * to the key's last segment — the same fallback rule as `t`.
   */
  readonly tCount = (key: string, count: number): string => {
    const category = new Intl.PluralRules(this.bundle()?.language ?? 'en').select(count);
    return (
      this.bundle()?.messages[`${key}.${category}`] ??
      this.bundle()?.messages[key] ??
      key.slice(key.lastIndexOf('.') + 1)
    );
  };

  setLang(next: string): void {
    localStorage.setItem(LANG_STORAGE_KEY, next);
    this.langState.set(next);
  }

  /** Revalidates the bundle (cheap 304 when unchanged) — call after message writes. */
  refresh(): void {
    this.reloadTick.update((tick) => tick + 1);
  }
}
