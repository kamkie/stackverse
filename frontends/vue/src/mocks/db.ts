// In-memory data behind the MSW handlers. Message content is imported from
// spec/messages/*.json — the contract's seed files — so the mock cannot drift
// from what real backends serve.
import seedEn from "../../../../spec/messages/en.json";
import seedPl from "../../../../spec/messages/pl.json";
import type { components } from "../api/schema";

type Bookmark = components["schemas"]["Bookmark"];
type Report = components["schemas"]["Report"];
type UserAccount = components["schemas"]["UserAccount"];
type AuditEntry = components["schemas"]["AuditEntry"];
type Message = components["schemas"]["Message"];

export const SEED_MESSAGES: Record<string, Record<string, string>> = {
  en: seedEn,
  pl: seedPl,
};

let sequence = 0;

/** Deterministic UUID-shaped ids, stable within a db lifetime. */
export function nextId(): string {
  sequence += 1;
  return `00000000-0000-4000-8000-${String(sequence).padStart(12, "0")}`;
}

function daysAgo(days: number, hour = 12): string {
  const date = new Date();
  date.setUTCDate(date.getUTCDate() - days);
  date.setUTCHours(hour, 0, 0, 0);
  return date.toISOString();
}

export interface Db {
  bookmarks: Bookmark[];
  reports: Report[];
  users: UserAccount[];
  audit: AuditEntry[];
  messages: Message[];
  /** Bumped on every message write; drives the bundle/messages ETags. */
  messagesVersion: number;
  /** Bumped on every mutation; drives the stats ETag. */
  statsVersion: number;
}

function bookmark(
  partial: Pick<Bookmark, "owner" | "title" | "url"> &
    Partial<Pick<Bookmark, "notes" | "tags" | "visibility" | "status">> & {
      ageDays: number;
    },
): Bookmark {
  const createdAt = daysAgo(partial.ageDays);
  return {
    id: nextId(),
    owner: partial.owner,
    title: partial.title,
    url: partial.url,
    ...(partial.notes !== undefined ? { notes: partial.notes } : {}),
    tags: partial.tags ?? [],
    visibility: partial.visibility ?? "private",
    status: partial.status ?? "active",
    createdAt,
    updatedAt: createdAt,
  };
}

function createDb(): Db {
  sequence = 0;

  const bookmarks: Bookmark[] = [
    // demo's bookmarks — enough volume to exercise "load more" (page size 20).
    ...Array.from({ length: 23 }, (_, i) =>
      bookmark({
        owner: "demo",
        title: `Reading list #${23 - i}`,
        url: `https://example.com/articles/${23 - i}`,
        tags: i % 3 === 0 ? ["reading", "dev"] : ["reading"],
        visibility: i % 4 === 0 ? "public" : "private",
        ageDays: i + 3,
      }),
    ),
    bookmark({
      owner: "demo",
      title: "MDN Web Docs",
      url: "https://developer.mozilla.org/",
      notes: "The reference.",
      tags: ["dev", "reference"],
      visibility: "public",
      ageDays: 1,
    }),
    bookmark({
      owner: "demo",
      title: "RFC 9457 — Problem Details",
      url: "https://www.rfc-editor.org/rfc/rfc9457",
      notes: "Error shape used by the whole API.",
      tags: ["dev", "http"],
      visibility: "private",
      ageDays: 2,
    }),
    bookmark({
      owner: "demo",
      title: "Get rich quick!!!",
      url: "https://example.com/spam",
      notes: "Hidden by moderation for a reason.",
      tags: ["misc"],
      visibility: "public",
      status: "hidden",
      ageDays: 6,
    }),
    bookmark({
      owner: "carol",
      title: "CSS grid garden",
      url: "https://cssgridgarden.com/",
      tags: ["css", "games"],
      visibility: "public",
      ageDays: 0,
    }),
    bookmark({
      owner: "carol",
      title: "Suspicious crypto site",
      url: "https://example.com/crypto",
      notes: "Reported by two users.",
      tags: ["misc"],
      visibility: "public",
      ageDays: 4,
    }),
  ];

  const reported = bookmarks.find((b) => b.title === "Suspicious crypto site");

  const reports: Report[] = reported
    ? [
        {
          id: nextId(),
          bookmarkId: reported.id,
          reporter: "demo",
          reason: "spam",
          comment: "Looks like a scam.",
          status: "open",
          createdAt: daysAgo(2),
        },
        {
          id: nextId(),
          bookmarkId: reported.id,
          reporter: "moderator",
          reason: "other",
          status: "open",
          createdAt: daysAgo(1),
        },
      ]
    : [];

  const users: UserAccount[] = [
    { username: "demo", firstSeen: daysAgo(30), lastSeen: daysAgo(0), status: "active", bookmarkCount: bookmarks.filter((b) => b.owner === "demo").length },
    { username: "moderator", firstSeen: daysAgo(28), lastSeen: daysAgo(1), status: "active", bookmarkCount: 0 },
    { username: "admin", firstSeen: daysAgo(28), lastSeen: daysAgo(0), status: "active", bookmarkCount: 0 },
    { username: "carol", firstSeen: daysAgo(14), lastSeen: daysAgo(4), status: "active", bookmarkCount: bookmarks.filter((b) => b.owner === "carol").length },
    { username: "mallory", firstSeen: daysAgo(20), lastSeen: daysAgo(9), status: "blocked", blockedReason: "Repeated spam.", bookmarkCount: 0 },
  ];

  const audit: AuditEntry[] = [
    {
      id: nextId(),
      actor: "admin",
      action: "user.blocked",
      targetType: "user",
      targetId: "mallory",
      detail: { reason: "Repeated spam." },
      createdAt: daysAgo(9),
    },
  ];

  const messages: Message[] = Object.entries(SEED_MESSAGES).flatMap(
    ([language, entries]) =>
      Object.entries(entries).map(([key, text]) => ({
        id: nextId(),
        key,
        language,
        text,
        createdAt: daysAgo(30),
        updatedAt: daysAgo(30),
      })),
  );

  return {
    bookmarks,
    reports,
    users,
    audit,
    messages,
    // Derived from the seed content so ETags change when spec/messages/*.json
    // change — otherwise a browser holding a cached bundle would 304 forever.
    messagesVersion: seedHash(),
    statsVersion: 1,
  };
}

function seedHash(): number {
  const text = JSON.stringify(SEED_MESSAGES);
  let hash = 0;
  for (let i = 0; i < text.length; i++) {
    hash = (hash * 31 + text.charCodeAt(i)) | 0;
  }
  return Math.abs(hash);
}

export let db: Db = createDb();

export function resetDb(): void {
  db = createDb();
}
