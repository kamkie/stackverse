// Backoffice: lazy account provisioning (SPEC rule 16), blocking (rule 17),
// and the append-only audit trail (rule 18). `mentor` is the guinea pig so
// blocking never disturbs the other fixtures' users.
import { createBookmark, expect, expectProblem, test, uid } from "./fixtures";

interface UserAccount {
  username: string;
  firstSeen: string;
  lastSeen: string;
  status: "active" | "blocked";
  blockedReason?: string;
  bookmarkCount: number;
}

interface AuditEntry {
  id: string;
  actor: string;
  action: string;
  targetType: string;
  targetId: string;
  detail?: Record<string, unknown>;
  createdAt: string;
}

test("accounts are lazily provisioned from JWTs", async ({ mentor, admin }) => {
  const before = Date.now();
  expect((await mentor.get("/api/v1/me")).status()).toBe(200);

  const response = await admin.get("/api/v1/admin/users/mentor");
  expect(response.status(), await response.text()).toBe(200);
  const account = (await response.json()) as UserAccount;
  expect(account.status).toBe("active");
  expect(Date.parse(account.firstSeen)).toBeLessThanOrEqual(Date.parse(account.lastSeen));
  // lastSeen was just touched by the /me call above
  expect(Date.parse(account.lastSeen)).toBeGreaterThan(before - 60_000);

  const directory = (await (
    await admin.get("/api/v1/admin/users?q=mento&size=100")
  ).json()) as { items: UserAccount[] };
  expect(directory.items.map((item) => item.username)).toContain("mentor");

  await expectProblem(await admin.get(`/api/v1/admin/users/ghost-${uid()}`), 404);
});

test("blocking requires a reason", async ({ admin }) => {
  const problem = await expectProblem(
    await admin.put("/api/v1/admin/users/mentor/status", { data: { status: "blocked" } }),
    400,
  );
  expect((problem.errors ?? []).map((error) => error.field)).toContain("reason");
});

test("a blocked user gets 403 everywhere authenticated; anonymous surface unaffected", async ({ mentor, admin, anon }) => {
  const blocked = await admin.put("/api/v1/admin/users/mentor/status", {
    data: { status: "blocked", reason: "conformance blocking check" },
  });
  expect(blocked.status(), await blocked.text()).toBe(200);
  expect(((await blocked.json()) as UserAccount).blockedReason).toBe("conformance blocking check");

  try {
    // takes effect on the next request, on every authenticated endpoint
    await expectProblem(await mentor.get("/api/v1/me"), 403);
    await expectProblem(
      await mentor.post("/api/v1/bookmarks", {
        data: { url: "https://example.com/blocked", title: "blocked" },
      }),
      403,
    );
    // identity is Keycloak's business, the app-level block is not
    expect((await anon.get("/api/v1/bookmarks?visibility=public")).status()).toBe(200);
  } finally {
    const unblocked = await admin.put("/api/v1/admin/users/mentor/status", {
      data: { status: "active" },
    });
    expect(unblocked.status(), await unblocked.text()).toBe(200);
  }

  expect((await mentor.get("/api/v1/me")).status()).toBe(200);
  const account = (await (await admin.get("/api/v1/admin/users/mentor")).json()) as UserAccount;
  expect(account.status).toBe("active");
  expect(account.blockedReason).toBeUndefined();
});

test("admins cannot block themselves", async ({ admin }) => {
  await expectProblem(
    await admin.put("/api/v1/admin/users/admin/status", {
      data: { status: "blocked", reason: "should not work" },
    }),
    409,
  );
});

test("blocking an unknown account is a 404", async ({ admin }) => {
  await expectProblem(
    await admin.put(`/api/v1/admin/users/ghost-${uid()}/status`, {
      data: { status: "blocked", reason: "no such user" },
    }),
    404,
  );
});

test("backoffice mutations land in the audit trail with filters, newest first", async ({ demo, moderator, admin }) => {
  const runStart = new Date().toISOString();

  // produce one audited mutation of each flavor
  const bookmark = await createBookmark(demo, {
    url: "https://example.com/audited",
    title: `audited ${uid()}`,
    visibility: "public",
  });
  await moderator.put(`/api/v1/admin/bookmarks/${bookmark.id}/status`, { data: { status: "hidden" } });
  await moderator.put(`/api/v1/admin/bookmarks/${bookmark.id}/status`, { data: { status: "active" } });

  const log = (await (
    await admin.get(`/api/v1/admin/audit-log?actor=moderator&targetId=${bookmark.id}&size=100`)
  ).json()) as { items: AuditEntry[] };
  expect(log.items.length).toBeGreaterThanOrEqual(2);
  for (const entry of log.items) {
    expect(entry.actor).toBe("moderator");
    expect(entry.targetId).toBe(bookmark.id);
    expect(entry.action).toMatch(/^[a-z-]+\.[a-z-]+$/);
    expect(Date.parse(entry.createdAt)).not.toBeNaN();
  }
  const stamps = log.items.map((entry) => Date.parse(entry.createdAt));
  expect([...stamps].sort((a, b) => b - a)).toEqual(stamps);

  // action + time-range filters
  const filtered = (await (
    await admin.get(`/api/v1/admin/audit-log?action=bookmark.status-changed&from=${runStart}&size=100`)
  ).json()) as { items: AuditEntry[] };
  expect(filtered.items.some((entry) => entry.targetId === bookmark.id)).toBe(true);
  for (const entry of filtered.items) {
    expect(entry.action).toBe("bookmark.status-changed");
    expect(Date.parse(entry.createdAt)).toBeGreaterThanOrEqual(Date.parse(runStart) - 1000);
  }
});

test("the audit trail is append-only — no mutation routes exist", async ({ admin }) => {
  const log = (await (await admin.get("/api/v1/admin/audit-log?size=1")).json()) as {
    items: AuditEntry[];
  };
  const target = log.items[0]?.id ?? "00000000-0000-0000-0000-000000000000";
  expect([404, 405]).toContain((await admin.delete(`/api/v1/admin/audit-log/${target}`)).status());
  expect([404, 405]).toContain(
    (await admin.put(`/api/v1/admin/audit-log/${target}`, { data: {} })).status(),
  );
});
