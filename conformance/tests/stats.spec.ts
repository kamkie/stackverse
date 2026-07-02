// Dashboard stats (SPEC rule 19): totals, a 30-day zero-filled daily series,
// top tags, and ETag revalidation as for messages (rule 10).
import { createBookmark, expect, expectProblem, test, uid } from "./fixtures";

interface AdminStats {
  totals: {
    users: number;
    bookmarks: number;
    publicBookmarks: number;
    hiddenBookmarks: number;
    openReports: number;
  };
  daily: { date: string; bookmarksCreated: number; activeUsers: number }[];
  topTags: { tag: string; count: number }[];
}

const DAY_MILLISECONDS = 24 * 60 * 60 * 1000;

test("stats are moderator-level", async ({ anon, demo, moderator, admin }) => {
  await expectProblem(await anon.get("/api/v1/admin/stats"), 401);
  await expectProblem(await demo.get("/api/v1/admin/stats"), 403);
  expect((await moderator.get("/api/v1/admin/stats")).status()).toBe(200);
  expect((await admin.get("/api/v1/admin/stats")).status()).toBe(200);
});

test("the daily series covers the last 30 days, zero-filled, oldest first", async ({ moderator }) => {
  const response = await moderator.get("/api/v1/admin/stats");
  const stats = (await response.json()) as AdminStats;

  for (const value of Object.values(stats.totals)) {
    expect(typeof value).toBe("number");
    expect(value).toBeGreaterThanOrEqual(0);
  }

  expect(stats.daily).toHaveLength(30);
  // strictly consecutive dates ending today (clock-skew tolerant on the edge)
  for (let i = 1; i < stats.daily.length; i++) {
    const previous = Date.parse(stats.daily[i - 1]?.date ?? "");
    const current = Date.parse(stats.daily[i]?.date ?? "");
    expect(current - previous).toBe(DAY_MILLISECONDS);
  }
  const last = Date.parse(stats.daily[29]?.date ?? "");
  expect(Math.abs(Date.now() - last)).toBeLessThan(2 * DAY_MILLISECONDS);
  for (const day of stats.daily) {
    expect(Number.isInteger(day.bookmarksCreated)).toBe(true);
    expect(Number.isInteger(day.activeUsers)).toBe(true);
  }

  expect(stats.topTags.length).toBeLessThanOrEqual(10);
  const counts = stats.topTags.map((entry) => entry.count);
  expect([...counts].sort((a, b) => b - a)).toEqual(counts);
});

test("stats revalidate with ETag and any data change invalidates it", async ({ demo, moderator }) => {
  const first = await moderator.get("/api/v1/admin/stats");
  const etag = first.headers()["etag"] ?? "";
  expect(etag).toBeTruthy();
  expect(first.headers()["cache-control"]).toContain("no-cache");
  const before = (await first.json()) as AdminStats;

  const revalidated = await moderator.get("/api/v1/admin/stats", {
    headers: { "If-None-Match": etag },
  });
  expect(revalidated.status()).toBe(304);
  expect(await revalidated.text()).toBe("");

  await createBookmark(demo, { url: "https://example.com/stat", title: `stat ${uid()}` });

  const after = await moderator.get("/api/v1/admin/stats", {
    headers: { "If-None-Match": etag },
  });
  expect(after.status(), "a new bookmark must invalidate the stats ETag").toBe(200);
  const changed = (await after.json()) as AdminStats;
  expect(changed.totals.bookmarks).toBeGreaterThan(before.totals.bookmarks);
  expect(changed.daily[29]?.bookmarksCreated).toBeGreaterThanOrEqual(1);
});
