// Test double for the message-bundle fetch: I18n loads its bundle over plain
// fetch (not HttpClient), so specs stub globalThis.fetch with the real seed
// content from spec/messages — the same files backends import on startup.
import { vi } from 'vitest';
import en from '../../../../spec/messages/en.json';
import pl from '../../../../spec/messages/pl.json';

const SEEDS: Record<string, Record<string, string>> = { en, pl };

export interface BundleFetchStub {
  /** Every If-None-Match value the stub has seen, in request order. */
  readonly ifNoneMatch: (string | null)[];
  restore(): void;
}

/**
 * Stubs `globalThis.fetch` to answer `/api/v1/messages/bundle` from the seed
 * files, with a fixed ETag per language and 304 on a matching If-None-Match.
 * Other URLs are rejected so a spec never silently hits the network.
 */
export function stubBundleFetch(): BundleFetchStub {
  const ifNoneMatch: (string | null)[] = [];
  const original = globalThis.fetch;
  globalThis.fetch = vi.fn(async (input: RequestInfo | URL, init?: RequestInit) => {
    const url = new URL(input instanceof Request ? input.url : String(input), 'http://localhost');
    if (url.pathname !== '/api/v1/messages/bundle') {
      throw new Error(`Unexpected fetch in test: ${url.pathname}`);
    }
    const language = url.searchParams.get('lang') ?? 'en';
    const messages = SEEDS[language] ?? SEEDS['en'];
    const etag = `"bundle-${language}"`;
    const sent = new Headers(init?.headers ?? (input instanceof Request ? input.headers : {}));
    ifNoneMatch.push(sent.get('If-None-Match'));
    if (sent.get('If-None-Match') === etag) {
      return new Response(null, { status: 304 });
    }
    return new Response(
      JSON.stringify({ language: language in SEEDS ? language : 'en', messages }),
      {
        status: 200,
        headers: { 'Content-Type': 'application/json', ETag: etag },
      },
    );
  }) as typeof fetch;
  return {
    ifNoneMatch,
    restore() {
      globalThis.fetch = original;
    },
  };
}

/** Resolves once pending microtasks/timers of the current turn have run. */
export function flushAsync(): Promise<void> {
  return new Promise((resolve) => setTimeout(resolve, 0));
}
