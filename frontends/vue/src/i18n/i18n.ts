import { computed, ref } from "vue";
import type { MessageBundle } from "../types";

interface CachedBundle {
  etag: string | null;
  bundle: MessageBundle;
}

const LANG_STORAGE_KEY = "stackverse.lang";
const bundleStorageKey = (lang: string | null) => `stackverse.bundle.${lang ?? "auto"}`;

export const selectedLanguage = ref<string | null>(readStoredLanguage());
export const bundle = ref<MessageBundle | null>(null);
export const resolvedLanguage = computed(() => bundle.value?.language ?? "en");

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

  const fresh: CachedBundle = {
    etag: response.headers.get("ETag"),
    bundle: (await response.json()) as MessageBundle,
  };
  writeCachedBundle(lang, fresh);
  return fresh;
}

export async function loadBundle(): Promise<void> {
  try {
    bundle.value = (await fetchBundle(selectedLanguage.value)).bundle;
  } catch {
    bundle.value ??= { language: "en", messages: {} };
  }
  document.documentElement.lang = bundle.value.language;
  document.title = bundle.value.messages["ui.app.title"] ?? document.title;
}

export async function setLanguage(lang: string): Promise<void> {
  try {
    localStorage.setItem(LANG_STORAGE_KEY, lang);
  } catch {
    // Storage unavailable: the language still changes for this page load.
  }
  selectedLanguage.value = lang;
  await loadBundle();
}

export function t(key: string): string {
  return bundle.value?.messages[key] ?? key.slice(key.lastIndexOf(".") + 1);
}

export function tCount(key: string, count: number): string {
  const category = new Intl.PluralRules(resolvedLanguage.value).select(count);
  return (
    bundle.value?.messages[`${key}.${category}`] ??
    bundle.value?.messages[key] ??
    key.slice(key.lastIndexOf(".") + 1)
  );
}
