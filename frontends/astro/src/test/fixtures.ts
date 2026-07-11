import { setI18n } from "../lib/i18n";
import type { Bookmark, Page, Report, UserAccount } from "../lib/types";

export const NOW = "2026-07-11T12:00:00Z";

export function readyI18n(messages: Record<string, string> = {}): void {
  setI18n({
    lang: "en",
    resolvedLanguage: "en",
    messages,
    ready: true,
  });
}

export function jsonResponse(body: unknown, init: ResponseInit = {}): Response {
  return new Response(JSON.stringify(body), {
    ...init,
    headers: { "Content-Type": "application/json", ...init.headers },
  });
}

export function bookmark(overrides: Partial<Bookmark> = {}): Bookmark {
  return {
    id: "bookmark-1",
    owner: "demo",
    url: "https://example.com/solid",
    title: "Solid resource",
    notes: "Fine-grained reactivity",
    tags: ["solid", "typescript"],
    visibility: "public",
    status: "active",
    createdAt: NOW,
    updatedAt: NOW,
    ...overrides,
  };
}

export function report(overrides: Partial<Report> = {}): Report {
  return {
    id: "report-1",
    bookmarkId: "bookmark-1",
    reporter: "reporter",
    reason: "spam",
    comment: "Repeated promotion",
    status: "open",
    createdAt: NOW,
    ...overrides,
  };
}

export function userAccount(overrides: Partial<UserAccount> = {}): UserAccount {
  return {
    username: "reader",
    firstSeen: NOW,
    lastSeen: NOW,
    status: "active",
    bookmarkCount: 2,
    ...overrides,
  };
}

export function page<T>(items: T[], overrides: Partial<Page<T>> = {}): Page<T> {
  return {
    items,
    page: 0,
    size: 20,
    totalItems: items.length,
    totalPages: items.length > 0 ? 1 : 0,
    ...overrides,
  };
}
