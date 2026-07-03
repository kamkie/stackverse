#!/usr/bin/env node

const backendUrl = trimUrl(process.env.BACKEND_URL || "http://localhost:8080");
const keycloakUrl = trimUrl(process.env.KEYCLOAK_URL || "http://localhost:8180");

const users = ["demo", "mentor", "moderator", "admin"];
const tokens = new Map();
const touched = {
  users: [],
  bookmarksCreated: 0,
  bookmarksUpdated: 0,
  bookmarksReused: 0,
  reportsCreated: 0,
  reportsReused: 0,
  reportsResolved: 0,
  restored: 0,
};

const bookmarkSeeds = [
  {
    owner: "demo",
    input: {
      url: "https://stackverse.local/demo/architecture-notes",
      title: "Stackverse Seed: Demo Architecture Notes",
      notes: "Public demo bookmark for the feed, API filtering, and tag counts.",
      tags: ["stackverse", "architecture", "api"],
      visibility: "public",
    },
  },
  {
    owner: "demo",
    input: {
      url: "https://stackverse.local/demo/private-reading",
      title: "Stackverse Seed: Demo Private Reading",
      notes: "Private demo bookmark that should stay out of the public feed.",
      tags: ["private", "reading", "api"],
      visibility: "private",
    },
  },
  {
    owner: "admin",
    input: {
      url: "https://stackverse.local/admin/release-radar",
      title: "Stackverse Seed: Admin Release Radar",
      notes: "Public admin-owned bookmark used as the open report target.",
      tags: ["stackverse", "release", "api"],
      visibility: "public",
    },
  },
  {
    owner: "admin",
    input: {
      url: "https://stackverse.local/admin/operations-runbook",
      title: "Stackverse Seed: Admin Operations Runbook",
      notes: "Private admin bookmark for owner-only and dashboard data.",
      tags: ["ops", "runbook", "stackverse"],
      visibility: "private",
    },
  },
];

const moderatedSeed = {
  owner: "demo",
  input: {
    url: "https://stackverse.local/demo/moderated-bookmark",
    title: "Stackverse Seed: Moderated Bookmark",
    notes: "Seed bookmark that is hidden through the normal moderation workflow.",
    tags: ["moderation", "spam", "seed"],
    visibility: "public",
  },
};

const openReportComment = "Stackverse seed: open report for the moderator queue.";
const hiddenReportComment = "Stackverse seed: report resolved as actioned to create a hidden bookmark.";

main().catch((error) => {
  console.error(error instanceof Error ? error.message : String(error));
  process.exitCode = 1;
});

async function main() {
  console.log(`Seeding Stackverse demo data against ${backendUrl}`);
  await ensureUsersActive();

  const bookmarks = new Map();
  for (const seed of bookmarkSeeds) {
    const bookmark = await ensureBookmark(seed.owner, seed.input);
    bookmarks.set(seed.input.title, bookmark);
  }

  const openTarget = bookmarks.get("Stackverse Seed: Admin Release Radar");
  const openReport = await ensureOpenReport("mentor", openTarget, {
    reason: "broken-link",
    comment: openReportComment,
  });

  const moderatedBookmark = await ensureBookmark(moderatedSeed.owner, moderatedSeed.input, {
    allowHidden: true,
  });
  const hiddenBookmark = await ensureHiddenBookmark(moderatedBookmark);

  const stats = await api("admin", "GET", "/api/v1/admin/stats");
  const audit = await api("admin", "GET", "/api/v1/admin/audit-log?size=5");

  console.log("");
  console.log("Seed complete.");
  console.log(`Users touched: ${touched.users.join(", ")}`);
  console.log(
    `Bookmarks: ${touched.bookmarksCreated} created, ${touched.bookmarksUpdated} updated, ${touched.bookmarksReused} reused`,
  );
  console.log(`Reports: ${touched.reportsCreated} created, ${touched.reportsReused} reused, ${touched.reportsResolved} resolved`);
  if (touched.restored > 0) console.log(`Seed bookmarks restored from hidden state: ${touched.restored}`);
  console.log(`Open report: ${openReport.id} on ${openReport.bookmarkId}`);
  console.log(`Hidden bookmark: ${hiddenBookmark.id}`);
  console.log(
    `Stats totals: ${stats.body.totals.users} users, ${stats.body.totals.bookmarks} bookmarks, ${stats.body.totals.publicBookmarks} public, ${stats.body.totals.hiddenBookmarks} hidden, ${stats.body.totals.openReports} open reports`,
  );
  console.log(`Audit entries visible to admin: ${audit.body.totalItems}`);
}

async function ensureUsersActive() {
  await api("admin", "GET", "/api/v1/me");
  touched.users.push("admin");

  for (const user of users.filter((value) => value !== "admin")) {
    const response = await api(user, "GET", "/api/v1/me", undefined, [200, 403]);
    if (response.status === 403) {
      await api("admin", "PUT", `/api/v1/admin/users/${encodeURIComponent(user)}/status`, {
        status: "active",
      });
      await api(user, "GET", "/api/v1/me");
    }
    touched.users.push(user);
  }
}

async function ensureBookmark(owner, input, options = {}) {
  const existing = await findOwnBookmark(owner, input.title);
  if (!existing) {
    const created = await api(owner, "POST", "/api/v1/bookmarks", input, [201]);
    touched.bookmarksCreated += 1;
    return created.body;
  }

  if (existing.status === "hidden") {
    if (options.allowHidden && bookmarkMatches(existing, input)) {
      touched.bookmarksReused += 1;
      return existing;
    }
    await api("moderator", "PUT", `/api/v1/admin/bookmarks/${existing.id}/status`, {
      status: "active",
      note: "Stackverse seed: restore active seed bookmark.",
    });
    touched.restored += 1;
  }

  const latest = existing.status === "hidden" ? await requiredBookmark(owner, input.title) : existing;
  if (bookmarkMatches(latest, input)) {
    touched.bookmarksReused += 1;
    return latest;
  }

  const updated = await api(owner, "PUT", `/api/v1/bookmarks/${latest.id}`, input);
  touched.bookmarksUpdated += 1;
  return updated.body;
}

async function ensureOpenReport(reporter, bookmark, input) {
  const existing = await findOpenReport(reporter, bookmark.id);
  if (existing) {
    touched.reportsReused += 1;
    return existing;
  }

  const created = await api(reporter, "POST", `/api/v1/bookmarks/${bookmark.id}/reports`, input, [201, 409]);
  if (created.status === 201) {
    touched.reportsCreated += 1;
    return created.body;
  }

  const conflicted = await findOpenReport(reporter, bookmark.id);
  if (!conflicted) {
    throw new Error(`Open report conflict for ${bookmark.id}, but no matching report was listed for ${reporter}`);
  }
  touched.reportsReused += 1;
  return conflicted;
}

async function ensureHiddenBookmark(bookmark) {
  if (bookmark.status === "hidden") return bookmark;

  const report = await ensureOpenReport("mentor", bookmark, {
    reason: "spam",
    comment: hiddenReportComment,
  });
  await api("moderator", "PUT", `/api/v1/admin/reports/${report.id}`, {
    resolution: "actioned",
    note: "Stackverse seed: actioned to create a hidden bookmark.",
  });
  touched.reportsResolved += 1;

  const hidden = await requiredBookmark("demo", moderatedSeed.input.title);
  if (hidden.status !== "hidden") {
    throw new Error(`Expected ${hidden.title} to be hidden after resolving report ${report.id}`);
  }
  return hidden;
}

async function findOwnBookmark(owner, title) {
  const query = new URLSearchParams({ q: title, size: "100" });
  const response = await api(owner, "GET", `/api/v1/bookmarks?${query}`);
  return response.body.items.find((bookmark) => bookmark.title === title);
}

async function requiredBookmark(owner, title) {
  const bookmark = await findOwnBookmark(owner, title);
  if (!bookmark) throw new Error(`Expected to find bookmark "${title}" for ${owner}`);
  return bookmark;
}

async function findOpenReport(reporter, bookmarkId) {
  const response = await api(reporter, "GET", "/api/v1/reports?status=open&size=100");
  return response.body.items.find((report) => report.bookmarkId === bookmarkId);
}

function bookmarkMatches(bookmark, input) {
  return (
    bookmark.url === input.url &&
    bookmark.title === input.title &&
    (bookmark.notes || "") === (input.notes || "") &&
    bookmark.visibility === input.visibility &&
    sameTags(bookmark.tags, input.tags || [])
  );
}

function sameTags(left, right) {
  const a = [...left].sort();
  const b = [...right].sort();
  return a.length === b.length && a.every((value, index) => value === b[index]);
}

async function api(user, method, path, body, okStatuses = [200]) {
  const headers = {};
  if (user) headers.authorization = `Bearer ${await accessToken(user)}`;
  const init = { method, headers };
  if (body !== undefined) {
    headers["content-type"] = "application/json";
    init.body = JSON.stringify(body);
  }

  const response = await fetch(`${backendUrl}${path}`, init);
  const text = await response.text();
  const parsedBody = parseBody(text);
  if (!okStatuses.includes(response.status)) {
    throw new Error(
      `${method} ${path} as ${user || "anonymous"} expected ${okStatuses.join("/")} but got ${response.status}: ${text}`,
    );
  }
  return { status: response.status, body: parsedBody, headers: response.headers };
}

async function accessToken(user) {
  const cached = tokens.get(user);
  if (cached && cached.expiresAt - Date.now() > 30_000) return cached.value;

  const response = await fetch(`${keycloakUrl}/realms/stackverse/protocol/openid-connect/token`, {
    method: "POST",
    headers: { "content-type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams({
      grant_type: "password",
      client_id: "stackverse-conformance",
      username: user,
      password: user,
    }),
  });
  const text = await response.text();
  if (!response.ok) {
    throw new Error(`Token request for ${user} failed with ${response.status}: ${text}`);
  }
  const body = parseBody(text);
  tokens.set(user, {
    value: body.access_token,
    expiresAt: Date.now() + body.expires_in * 1000,
  });
  return body.access_token;
}

function parseBody(text) {
  if (!text) return undefined;
  try {
    return JSON.parse(text);
  } catch {
    return text;
  }
}

function trimUrl(value) {
  return value.replace(/\/+$/, "");
}
