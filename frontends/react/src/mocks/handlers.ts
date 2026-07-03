// MSW handlers, typed against the generated OpenAPI schema via openapi-msw —
// paths, params, and response bodies that drift from spec/openapi.yaml fail
// to compile. The /auth/* endpoints are the gateway contract
// (docs/ARCHITECTURE.md), not part of the OpenAPI document, so they use
// plain msw handlers.
import { http as mswHttp, HttpResponse } from "msw";
import { createOpenApiHttp } from "openapi-msw";
import type { components, paths } from "../api/schema";
import { db, nextId } from "./db";
import { getCurrentUser, MOCK_USERS, setCurrentUser, type MockUserName } from "./state";

type Problem = components["schemas"]["Problem"];
type FieldError = NonNullable<Problem["errors"]>[number];
type BookmarkInput = components["schemas"]["BookmarkInput"];
type MessageInput = components["schemas"]["MessageInput"];

const http = createOpenApiHttp<paths>();

/* ---- Problem helpers --------------------------------------------------- */

function problem(status: number, title: string, detail?: string): Problem {
  return { type: "about:blank", status, title, ...(detail ? { detail } : {}) };
}

const unauthorized = () => problem(401, "Unauthorized");
const forbidden = () => problem(403, "Forbidden");
const notFound = () => problem(404, "Not Found");

function validationProblem(errors: FieldError[]): Problem {
  return { ...problem(400, "Validation failed"), errors };
}

/**
 * Message text from the (runtime-editable) messages table, resolved per
 * SPEC rule 8: requested language → en → the key itself.
 */
function lookupMessage(key: string, lang: string): string {
  const inLanguage = (language: string) =>
    db.messages.find((m) => m.key === key && m.language === language)?.text;
  return inLanguage(lang) ?? inLanguage("en") ?? key;
}

/** Language for a localized response body (SPEC rule 11: lang → Accept-Language → en). */
function requestLanguage(request: Request): string {
  return resolveLanguage(null, request.headers.get("Accept-Language"));
}

function fieldError(field: string, messageKey: string, lang: string): FieldError {
  return {
    field,
    messageKey,
    message: lookupMessage(messageKey, lang),
  };
}

/* ---- Auth & pagination helpers ------------------------------------------ */

function hasRole(role: "moderator" | "admin"): boolean {
  return getCurrentUser()?.roles.includes(role) === true;
}

/**
 * SPEC rule 17: a blocked account gets `403` on every authenticated call
 * (the anonymous public surface keeps working). Returns null when the caller
 * may proceed.
 */
function blockedGuard(request: Request): Response | null {
  const user = getCurrentUser();
  if (!user) return null;
  const account = db.users.find((u) => u.username === user.username);
  if (account?.status !== "blocked") return null;
  return HttpResponse.json(
    problem(
      403,
      "Forbidden",
      lookupMessage("error.account.blocked", requestLanguage(request)),
    ),
    { status: 403, headers: { "Content-Type": "application/problem+json" } },
  );
}

/**
 * Applied before the typed handlers: returning nothing lets MSW fall through
 * to them. Public message reads stay available to blocked users; everything
 * else under /api is an authenticated call once a session exists.
 */
const blockedGate = mswHttp.all("/api/*", ({ request }) => {
  const url = new URL(request.url);
  if (request.method === "GET" && url.pathname.startsWith("/api/v1/messages")) {
    return undefined;
  }
  return blockedGuard(request) ?? undefined;
});

function paginate<T>(items: T[], pageParam: string | null, sizeParam: string | null) {
  const page = Math.max(0, Number(pageParam ?? 0) || 0);
  const size = Math.min(100, Math.max(1, Number(sizeParam ?? 20) || 20));
  return {
    items: items.slice(page * size, (page + 1) * size),
    page,
    size,
    totalItems: items.length,
    totalPages: Math.ceil(items.length / size),
  };
}

function touchStats(): void {
  db.statsVersion += 1;
}

function writeAudit(
  action: string,
  targetType: string,
  targetId: string,
  detail?: Record<string, unknown>,
): void {
  db.audit.unshift({
    id: nextId(),
    actor: getCurrentUser()?.username ?? "?",
    action,
    targetType,
    targetId,
    ...(detail ? { detail } : {}),
    createdAt: new Date().toISOString(),
  });
  touchStats();
}

/* ---- Validation (mirrors docs/SPEC.md rules) ------------------------------ */

const TAG_PATTERN = /^[a-z0-9-]{1,30}$/;
const MESSAGE_KEY_PATTERN = /^[a-z0-9-]+(\.[a-z0-9-]+)*$/;
const LANGUAGE_PATTERN = /^[a-z]{2}$/;

function normalizeTags(tags: string[] | undefined): string[] {
  return [...new Set((tags ?? []).map((tag) => tag.trim().toLowerCase()))];
}

function validateBookmark(input: BookmarkInput, lang: string): FieldError[] {
  const errors: FieldError[] = [];
  if (!input.url) {
    errors.push(fieldError("url", "validation.url.required", lang));
  } else {
    let valid = input.url.length <= 2000;
    try {
      const url = new URL(input.url);
      valid &&= url.protocol === "http:" || url.protocol === "https:";
    } catch {
      valid = false;
    }
    if (!valid) errors.push(fieldError("url", "validation.url.invalid", lang));
  }
  if (!input.title || input.title.trim() === "") {
    errors.push(fieldError("title", "validation.title.required", lang));
  } else if (input.title.length > 200) {
    errors.push(fieldError("title", "validation.title.too-long", lang));
  }
  if (input.notes && input.notes.length > 4000) {
    errors.push(fieldError("notes", "validation.notes.too-long", lang));
  }
  const tags = normalizeTags(input.tags);
  if (tags.length > 10) {
    errors.push(fieldError("tags", "validation.tags.too-many", lang));
  } else if (tags.some((tag) => !TAG_PATTERN.test(tag))) {
    errors.push(fieldError("tags", "validation.tag.invalid", lang));
  }
  return errors;
}

function validateMessage(input: MessageInput, lang: string): FieldError[] {
  const errors: FieldError[] = [];
  if (!input.key || input.key.length > 150 || !MESSAGE_KEY_PATTERN.test(input.key)) {
    errors.push(fieldError("key", "validation.message.key.invalid", lang));
  }
  if (!input.language || !LANGUAGE_PATTERN.test(input.language)) {
    errors.push(fieldError("language", "validation.message.language.invalid", lang));
  }
  if (!input.text || input.text.trim() === "") {
    errors.push(fieldError("text", "validation.message.text.required", lang));
  }
  return errors;
}

/* ---- Bookmarks -------------------------------------------------------------- */

const byNewest = (a: { createdAt: string; id: string }, b: { createdAt: string; id: string }) =>
  b.createdAt.localeCompare(a.createdAt) || b.id.localeCompare(a.id);

const listBookmarksV2 = http.get("/api/v2/bookmarks", ({ query, response }) => {
  const user = getCurrentUser();
  const visibility = query.get("visibility");

  if (visibility !== "public" && !user) {
    return response(401).json(unauthorized());
  }

  let items =
    visibility === "public"
      ? db.bookmarks.filter((b) => b.visibility === "public" && b.status === "active")
      : db.bookmarks.filter((b) => b.owner === user?.username);

  for (const tag of query.getAll("tag")) {
    items = items.filter((b) => b.tags?.includes(tag));
  }
  const q = query.get("q")?.toLowerCase();
  if (q) {
    items = items.filter(
      (b) =>
        b.title.toLowerCase().includes(q) || b.notes?.toLowerCase().includes(q),
    );
  }
  items = [...items].sort(byNewest);

  const size = Math.min(100, Math.max(1, Number(query.get("size") ?? 20) || 20));
  const cursor = query.get("cursor");
  let start = 0;
  if (cursor) {
    let lastSeenId: string | null = null;
    try {
      lastSeenId = atob(cursor);
    } catch {
      lastSeenId = null;
    }
    const index = lastSeenId === null ? -1 : items.findIndex((b) => b.id === lastSeenId);
    if (index === -1) {
      return response(400).json(problem(400, "Invalid cursor"));
    }
    start = index + 1;
  }

  const slice = items.slice(start, start + size);
  const last = slice[slice.length - 1];
  const hasMore = start + size < items.length && last !== undefined;
  return response(200).json({
    items: slice,
    ...(hasMore ? { nextCursor: btoa(last.id) } : {}),
  });
});

const createBookmark = http.post("/api/v1/bookmarks", async ({ request, response }) => {
  const user = getCurrentUser();
  if (!user) return response(401).json(unauthorized());

  const input = await request.json();
  const errors = validateBookmark(input, requestLanguage(request));
  if (errors.length > 0) return response(400).json(validationProblem(errors));

  const now = new Date().toISOString();
  const bookmark = {
    id: nextId(),
    owner: user.username,
    url: input.url,
    title: input.title,
    ...(input.notes ? { notes: input.notes } : {}),
    tags: normalizeTags(input.tags),
    visibility: input.visibility ?? ("private" as const),
    status: "active" as const,
    createdAt: now,
    updatedAt: now,
  };
  db.bookmarks.push(bookmark);
  touchStats();
  return response(201).json(bookmark, {
    headers: { Location: `/api/v1/bookmarks/${bookmark.id}` },
  });
});

const getBookmark = http.get("/api/v1/bookmarks/{id}", ({ params, response }) => {
  const user = getCurrentUser();
  const bookmark = db.bookmarks.find((b) => b.id === params.id);
  // Owners always read their own; everyone else (moderators included) only
  // sees public + active. Anything else is 404 — existence is not disclosed.
  if (
    !bookmark ||
    (bookmark.owner !== user?.username &&
      !(bookmark.visibility === "public" && bookmark.status === "active"))
  ) {
    return response(404).json(notFound());
  }
  return response(200).json(bookmark);
});

const updateBookmark = http.put(
  "/api/v1/bookmarks/{id}",
  async ({ params, request, response }) => {
    const user = getCurrentUser();
    if (!user) return response(401).json(unauthorized());

    const bookmark = db.bookmarks.find((b) => b.id === params.id);
    if (!bookmark || bookmark.owner !== user.username) {
      return response(404).json(notFound());
    }

    const input = await request.json();
    const errors = validateBookmark(input, requestLanguage(request));
    if (errors.length > 0) return response(400).json(validationProblem(errors));

    if (bookmark.status === "hidden" && input.visibility === "public") {
      return response(409).json(
        problem(
          409,
          "Conflict",
          lookupMessage("error.bookmark.hidden-publish", requestLanguage(request)),
        ),
      );
    }

    bookmark.url = input.url;
    bookmark.title = input.title;
    if (input.notes) bookmark.notes = input.notes;
    else delete bookmark.notes;
    bookmark.tags = normalizeTags(input.tags);
    bookmark.visibility = input.visibility ?? "private";
    bookmark.updatedAt = new Date().toISOString();
    touchStats();
    return response(200).json(bookmark);
  },
);

const deleteBookmark = http.delete("/api/v1/bookmarks/{id}", ({ params, response }) => {
  const user = getCurrentUser();
  if (!user) return response(401).json(unauthorized());

  const index = db.bookmarks.findIndex(
    (b) => b.id === params.id && b.owner === user.username,
  );
  if (index === -1) return response(404).json(notFound());

  db.bookmarks.splice(index, 1);
  touchStats();
  return response(204).empty();
});

/* ---- Tags & me ------------------------------------------------------------------ */

const listTags = http.get("/api/v1/tags", ({ response }) => {
  const user = getCurrentUser();
  if (!user) return response(401).json(unauthorized());

  const counts = new Map<string, number>();
  for (const bookmark of db.bookmarks) {
    if (bookmark.owner !== user.username) continue;
    for (const tag of bookmark.tags ?? []) {
      counts.set(tag, (counts.get(tag) ?? 0) + 1);
    }
  }
  const tags = [...counts.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .map(([tag, count]) => ({ tag, count }));
  return response(200).json({ tags });
});

const getMe = http.get("/api/v1/me", ({ response }) => {
  const user = getCurrentUser();
  if (!user) return response(401).json(unauthorized());
  return response(200).json({ username: user.username, roles: user.roles });
});

/* ---- Reporting & moderation --------------------------------------------------------- */

const reportBookmark = http.post(
  "/api/v1/bookmarks/{id}/reports",
  async ({ params, request, response }) => {
    const user = getCurrentUser();
    if (!user) return response(401).json(unauthorized());

    const bookmark = db.bookmarks.find((b) => b.id === params.id);
    // Private or hidden bookmarks are 404 — existence is not disclosed.
    if (!bookmark || bookmark.visibility !== "public" || bookmark.status !== "active") {
      return response(404).json(notFound());
    }

    const input = await request.json();
    const lang = requestLanguage(request);
    const errors: FieldError[] = [];
    if (!["spam", "offensive", "broken-link", "other"].includes(input.reason)) {
      errors.push(fieldError("reason", "validation.report.reason.invalid", lang));
    }
    if (input.comment && input.comment.length > 1000) {
      errors.push(fieldError("comment", "validation.report.comment.too-long", lang));
    }
    if (errors.length > 0) return response(400).json(validationProblem(errors));

    const existing = db.reports.find(
      (r) =>
        r.bookmarkId === bookmark.id &&
        r.reporter === user.username &&
        r.status === "open",
    );
    if (existing) {
      return response(409).json(
        problem(409, "Conflict", "You already have an open report on this bookmark."),
      );
    }

    const report = {
      id: nextId(),
      bookmarkId: bookmark.id,
      reporter: user.username,
      reason: input.reason,
      ...(input.comment ? { comment: input.comment } : {}),
      status: "open" as const,
      createdAt: new Date().toISOString(),
    };
    db.reports.push(report);
    touchStats();
    return response(201).json(report);
  },
);

const listReports = http.get("/api/v1/admin/reports", ({ query, response }) => {
  if (!getCurrentUser()) return response(401).json(unauthorized());
  if (!hasRole("moderator")) return response(403).json(forbidden());

  const status = query.get("status") ?? "open";
  const items = db.reports
    .filter((r) => r.status === status)
    .sort((a, b) => a.createdAt.localeCompare(b.createdAt));
  return response(200).json(paginate(items, query.get("page"), query.get("size")));
});

const resolveReport = http.put(
  "/api/v1/admin/reports/{id}",
  async ({ params, request, response }) => {
    const user = getCurrentUser();
    if (!user) return response(401).json(unauthorized());
    if (!hasRole("moderator")) return response(403).json(forbidden());

    const report = db.reports.find((r) => r.id === params.id);
    if (!report) return response(404).json(notFound());
    if (report.status !== "open") {
      return response(409).json(problem(409, "Conflict", "The report is not open."));
    }

    const input = await request.json();
    const now = new Date().toISOString();
    const resolve = (target: typeof report) => {
      target.status = input.resolution;
      target.resolvedBy = user.username;
      target.resolvedAt = now;
      if (input.note) target.resolutionNote = input.note;
    };
    resolve(report);

    if (input.resolution === "actioned") {
      const bookmark = db.bookmarks.find((b) => b.id === report.bookmarkId);
      if (bookmark) {
        bookmark.status = "hidden";
        writeAudit("bookmark.status-changed", "bookmark", bookmark.id, {
          status: "hidden",
        });
      }
      // Sibling auto-resolution (docs/SPEC.md rule 14).
      for (const sibling of db.reports) {
        if (
          sibling.bookmarkId === report.bookmarkId &&
          sibling.status === "open"
        ) {
          resolve(sibling);
          writeAudit("report.resolved", "report", sibling.id, {
            resolution: "actioned",
            cascaded: true,
          });
        }
      }
    }
    writeAudit("report.resolved", "report", report.id, {
      resolution: input.resolution,
    });
    return response(200).json(report);
  },
);

const setBookmarkStatus = http.put(
  "/api/v1/admin/bookmarks/{id}/status",
  async ({ params, request, response }) => {
    if (!getCurrentUser()) return response(401).json(unauthorized());
    if (!hasRole("moderator")) return response(403).json(forbidden());

    const bookmark = db.bookmarks.find((b) => b.id === params.id);
    if (!bookmark) return response(404).json(notFound());

    const input = await request.json();
    bookmark.status = input.status;
    bookmark.updatedAt = new Date().toISOString();
    writeAudit("bookmark.status-changed", "bookmark", bookmark.id, {
      status: input.status,
    });
    return response(200).json(bookmark);
  },
);

/* ---- Backoffice: users, audit, stats --------------------------------------------------- */

const listUserAccounts = http.get("/api/v1/admin/users", ({ query, response }) => {
  if (!getCurrentUser()) return response(401).json(unauthorized());
  if (!hasRole("admin")) return response(403).json(forbidden());

  let items = [...db.users].sort((a, b) => b.lastSeen.localeCompare(a.lastSeen));
  const q = query.get("q")?.toLowerCase();
  if (q) items = items.filter((u) => u.username.toLowerCase().includes(q));
  const status = query.get("status");
  if (status) items = items.filter((u) => u.status === status);
  return response(200).json(paginate(items, query.get("page"), query.get("size")));
});

const getUserAccount = http.get(
  "/api/v1/admin/users/{username}",
  ({ params, response }) => {
    if (!getCurrentUser()) return response(401).json(unauthorized());
    if (!hasRole("admin")) return response(403).json(forbidden());

    const user = db.users.find((u) => u.username === params.username);
    if (!user) return response(404).json(notFound());
    return response(200).json(user);
  },
);

const setUserAccountStatus = http.put(
  "/api/v1/admin/users/{username}/status",
  async ({ params, request, response }) => {
    const caller = getCurrentUser();
    if (!caller) return response(401).json(unauthorized());
    if (!hasRole("admin")) return response(403).json(forbidden());

    const user = db.users.find((u) => u.username === params.username);
    if (!user) return response(404).json(notFound());

    const input = await request.json();
    if (input.status === "blocked" && params.username === caller.username) {
      return response(409).json(
        problem(409, "Conflict", "Admins cannot block themselves."),
      );
    }
    if (input.status === "blocked" && !input.reason?.trim()) {
      return response(400).json(
        validationProblem([
          fieldError("reason", "validation.block.reason.required", requestLanguage(request)),
        ]),
      );
    }

    user.status = input.status;
    if (input.status === "blocked" && input.reason) user.blockedReason = input.reason;
    else delete user.blockedReason;
    writeAudit(
      input.status === "blocked" ? "user.blocked" : "user.unblocked",
      "user",
      user.username,
      input.reason ? { reason: input.reason } : undefined,
    );
    return response(200).json(user);
  },
);

const listAuditEntries = http.get("/api/v1/admin/audit-log", ({ query, response }) => {
  if (!getCurrentUser()) return response(401).json(unauthorized());
  if (!hasRole("admin")) return response(403).json(forbidden());

  let items = [...db.audit].sort((a, b) => b.createdAt.localeCompare(a.createdAt));
  const actor = query.get("actor");
  if (actor) items = items.filter((e) => e.actor === actor);
  const action = query.get("action");
  if (action) items = items.filter((e) => e.action === action);
  const targetType = query.get("targetType");
  if (targetType) items = items.filter((e) => e.targetType === targetType);
  const targetId = query.get("targetId");
  if (targetId) items = items.filter((e) => e.targetId === targetId);
  const from = query.get("from");
  if (from) items = items.filter((e) => e.createdAt >= from);
  const to = query.get("to");
  if (to) items = items.filter((e) => e.createdAt <= to);
  return response(200).json(paginate(items, query.get("page"), query.get("size")));
});

const getAdminStats = http.get("/api/v1/admin/stats", ({ request, response }) => {
  if (!getCurrentUser()) return response(401).json(unauthorized());
  if (!hasRole("moderator")) return response(403).json(forbidden());

  const etag = `W/"stats-${db.statsVersion}"`;
  if (request.headers.get("If-None-Match") === etag) {
    return response(304).empty();
  }

  const daily = Array.from({ length: 30 }, (_, i) => {
    const date = new Date();
    date.setUTCDate(date.getUTCDate() - (29 - i));
    const day = date.toISOString().slice(0, 10);
    return {
      date: day,
      bookmarksCreated: db.bookmarks.filter((b) => b.createdAt.startsWith(day)).length,
      activeUsers: db.users.filter((u) => u.lastSeen.startsWith(day)).length,
    };
  });

  const counts = new Map<string, number>();
  for (const bookmark of db.bookmarks) {
    for (const tag of bookmark.tags ?? []) {
      counts.set(tag, (counts.get(tag) ?? 0) + 1);
    }
  }
  const topTags = [...counts.entries()]
    .sort((a, b) => b[1] - a[1] || a[0].localeCompare(b[0]))
    .slice(0, 10)
    .map(([tag, count]) => ({ tag, count }));

  return response(200).json(
    {
      totals: {
        users: db.users.length,
        bookmarks: db.bookmarks.length,
        publicBookmarks: db.bookmarks.filter((b) => b.visibility === "public").length,
        hiddenBookmarks: db.bookmarks.filter((b) => b.status === "hidden").length,
        openReports: db.reports.filter((r) => r.status === "open").length,
      },
      daily,
      topTags,
    },
    { headers: { ETag: etag, "Cache-Control": "no-cache" } },
  );
});

/* ---- Messages ------------------------------------------------------------------------------- */

function resolveLanguage(lang: string | null, acceptLanguage: string | null): string {
  const supported = new Set(db.messages.map((m) => m.language));
  if (lang && supported.has(lang)) return lang;
  // First supported language in quality order (SPEC rule 8).
  const ranked = (acceptLanguage ?? "")
    .split(",")
    .map((part) => {
      const [tag = "", ...params] = part.trim().split(";");
      const qParam = params.map((p) => p.trim()).find((p) => p.startsWith("q="));
      const q = qParam ? Number(qParam.slice(2)) : 1;
      return { code: tag.trim().toLowerCase().slice(0, 2), q: Number.isNaN(q) ? 0 : q };
    })
    .filter((entry) => entry.code && entry.q > 0)
    .sort((a, b) => b.q - a.q);
  for (const { code } of ranked) {
    if (supported.has(code)) return code;
  }
  return "en";
}

const getMessageBundle = http.get(
  "/api/v1/messages/bundle",
  ({ query, request, response }) => {
    const language = resolveLanguage(
      query.get("lang"),
      request.headers.get("Accept-Language"),
    );
    const etag = `W/"bundle-${language}-${db.messagesVersion}"`;
    const headers = {
      ETag: etag,
      "Cache-Control": "no-cache",
      "Content-Language": language,
    };
    if (request.headers.get("If-None-Match") === etag) {
      return response(304).empty({ headers });
    }

    // Keys missing in the resolved language fall back to their en text.
    const messages: Record<string, string> = {};
    for (const message of db.messages) {
      if (message.language === "en") messages[message.key] = message.text;
    }
    if (language !== "en") {
      for (const message of db.messages) {
        if (message.language === language) messages[message.key] = message.text;
      }
    }
    return response(200).json({ language, messages }, { headers });
  },
);

const listMessages = http.get("/api/v1/messages", ({ query, request, response }) => {
  const etag = `W/"messages-${db.messagesVersion}"`;
  const headers = { ETag: etag, "Cache-Control": "no-cache" };
  if (request.headers.get("If-None-Match") === etag) {
    return response(304).empty({ headers });
  }

  let items = [...db.messages].sort(
    (a, b) => a.key.localeCompare(b.key) || a.language.localeCompare(b.language),
  );
  const key = query.get("key");
  if (key) items = items.filter((m) => m.key === key);
  const q = query.get("q");
  if (q) {
    const needle = q.toLowerCase();
    items = items.filter(
      (m) => m.key.toLowerCase().includes(needle) || m.text.toLowerCase().includes(needle),
    );
  }
  const language = query.get("language");
  if (language) items = items.filter((m) => m.language === language);
  return response(200).json(paginate(items, query.get("page"), query.get("size")), {
    headers,
  });
});

const createMessage = http.post("/api/v1/messages", async ({ request, response }) => {
  if (!getCurrentUser()) return response(401).json(unauthorized());
  if (!hasRole("admin")) return response(403).json(forbidden());

  const input = await request.json();
  const errors = validateMessage(input, requestLanguage(request));
  if (errors.length > 0) return response(400).json(validationProblem(errors));
  if (db.messages.some((m) => m.key === input.key && m.language === input.language)) {
    return response(409).json(
      problem(409, "Conflict", "A message with this key and language already exists."),
    );
  }

  const now = new Date().toISOString();
  const message = {
    id: nextId(),
    key: input.key,
    language: input.language,
    text: input.text,
    ...(input.description ? { description: input.description } : {}),
    createdAt: now,
    updatedAt: now,
  };
  db.messages.push(message);
  db.messagesVersion += 1;
  writeAudit("message.created", "message", message.id, { key: message.key });
  return response(201).json(message, {
    headers: { Location: `/api/v1/messages/${message.id}` },
  });
});

const getMessage = http.get("/api/v1/messages/{id}", ({ params, request, response }) => {
  const message = db.messages.find((m) => m.id === params.id);
  if (!message) return response(404).json(notFound());
  const etag = `W/"message-${message.id}-${db.messagesVersion}"`;
  const headers = { ETag: etag, "Cache-Control": "no-cache" };
  if (request.headers.get("If-None-Match") === etag) {
    return response(304).empty({ headers });
  }
  return response(200).json(message, { headers });
});

const updateMessage = http.put(
  "/api/v1/messages/{id}",
  async ({ params, request, response }) => {
    if (!getCurrentUser()) return response(401).json(unauthorized());
    if (!hasRole("admin")) return response(403).json(forbidden());

    const message = db.messages.find((m) => m.id === params.id);
    if (!message) return response(404).json(notFound());

    const input = await request.json();
    const errors = validateMessage(input, requestLanguage(request));
    if (errors.length > 0) return response(400).json(validationProblem(errors));
    if (
      db.messages.some(
        (m) => m.id !== message.id && m.key === input.key && m.language === input.language,
      )
    ) {
      return response(409).json(
        problem(409, "Conflict", "A message with this key and language already exists."),
      );
    }

    message.key = input.key;
    message.language = input.language;
    message.text = input.text;
    if (input.description) message.description = input.description;
    else delete message.description;
    message.updatedAt = new Date().toISOString();
    db.messagesVersion += 1;
    writeAudit("message.updated", "message", message.id, { key: message.key });
    return response(200).json(message);
  },
);

const deleteMessage = http.delete("/api/v1/messages/{id}", ({ params, response }) => {
  if (!getCurrentUser()) return response(401).json(unauthorized());
  if (!hasRole("admin")) return response(403).json(forbidden());

  const index = db.messages.findIndex((m) => m.id === params.id);
  if (index === -1) return response(404).json(notFound());

  const [removed] = db.messages.splice(index, 1);
  db.messagesVersion += 1;
  if (removed) writeAudit("message.deleted", "message", removed.id, { key: removed.key });
  return response(204).empty();
});

/* ---- Gateway /auth endpoints (docs/ARCHITECTURE.md, not in the OpenAPI spec) ---- */

const LOGIN_AS_STORAGE_KEY = "stackverse.mock.login-as";
const SESSION_STORAGE_KEY = "stackverse.mock.session";

function persistSession(name: MockUserName | null): void {
  try {
    if (name) localStorage.setItem(SESSION_STORAGE_KEY, name);
    else localStorage.removeItem(SESSION_STORAGE_KEY);
  } catch {
    // No localStorage (node tests) — in-memory state is enough there.
  }
}

/** Restores the mock session persisted by the login handler (browser dev mode). */
export function restorePersistedSession(): void {
  try {
    const stored = localStorage.getItem(SESSION_STORAGE_KEY) as MockUserName | null;
    if (stored && stored in MOCK_USERS) setCurrentUser(MOCK_USERS[stored]);
  } catch {
    // ignore
  }
}

/**
 * Stand-in for the gateway's OIDC login. The identity comes from
 * localStorage["stackverse.mock.login-as"] = demo | moderator | admin.
 */
export function performMockLogin(): void {
  let name: MockUserName = "demo";
  try {
    const requested = localStorage.getItem(LOGIN_AS_STORAGE_KEY);
    if (requested && requested in MOCK_USERS) name = requested as MockUserName;
  } catch {
    // default to demo
  }
  setCurrentUser(MOCK_USERS[name]);
  persistSession(name);
}

const authHandlers = [
  mswHttp.get("/auth/session", () => {
    const user = getCurrentUser();
    return HttpResponse.json(
      user ? { authenticated: true, username: user.username } : { authenticated: false },
    );
  }),
  // Non-navigation fallback for /auth/login; the real full-page navigation is
  // handled by src/mocks/browser.ts (service workers ignore document requests).
  mswHttp.get("/auth/login", () => {
    performMockLogin();
    return new HttpResponse(null, { status: 302, headers: { Location: "/" } });
  }),
  mswHttp.post("/auth/logout", () => {
    setCurrentUser(null);
    persistSession(null);
    return new HttpResponse(null, { status: 204 });
  }),
];

export const handlers = [
  blockedGate,
  listBookmarksV2,
  createBookmark,
  getBookmark,
  updateBookmark,
  deleteBookmark,
  listTags,
  getMe,
  reportBookmark,
  listReports,
  resolveReport,
  setBookmarkStatus,
  listUserAccounts,
  getUserAccount,
  setUserAccountStatus,
  listAuditEntries,
  getAdminStats,
  getMessageBundle,
  listMessages,
  createMessage,
  getMessage,
  updateMessage,
  deleteMessage,
  ...authHandlers,
];
