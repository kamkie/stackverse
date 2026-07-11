import { describe, expect, it } from "vitest";
import type { components } from "../api/schema";
import { db } from "../mocks/db";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { apiRequest, responseJson } from "./http";

type AdminStats = components["schemas"]["AdminStats"];
type AuditPage = components["schemas"]["AuditPage"];
type Problem = components["schemas"]["Problem"];
type UserAccount = components["schemas"]["UserAccount"];
type UserAccountPage = components["schemas"]["UserAccountPage"];

describe("admin mock contract", () => {
  it("enforces the admin boundary for account reads and self-block protection", async () => {
    expect((await apiRequest("/api/v1/admin/users")).status).toBe(401);
    setCurrentUser(MOCK_USERS.moderator);
    expect((await apiRequest("/api/v1/admin/users")).status).toBe(403);

    setCurrentUser(MOCK_USERS.admin);
    const filtered = await responseJson<UserAccountPage>(
      await apiRequest("/api/v1/admin/users?q=ALLORY&status=blocked&page=0&size=1"),
    );
    expect(filtered).toMatchObject({ totalItems: 1, totalPages: 1 });
    expect(filtered.items[0]).toMatchObject({ username: "mallory", status: "blocked" });

    expect(
      await responseJson<UserAccount>(await apiRequest("/api/v1/admin/users/mallory")),
    ).toMatchObject({ username: "mallory", blockedReason: "Repeated spam." });
    expect((await apiRequest("/api/v1/admin/users/missing-user")).status).toBe(404);

    const selfBlock = await apiRequest("/api/v1/admin/users/admin/status", {
      method: "PUT",
      body: { status: "blocked", reason: "should never be accepted" },
    });
    expect(selfBlock.status).toBe(409);
    expect((await responseJson<Problem>(selfBlock)).detail).toBe(
      "Admins cannot block themselves.",
    );
  });

  it("requires a block reason and records block/unblock audit entries", async () => {
    setCurrentUser(MOCK_USERS.admin);

    const missingReason = await apiRequest("/api/v1/admin/users/carol/status", {
      method: "PUT",
      headers: { "Accept-Language": "pl" },
      body: { status: "blocked", reason: "   " },
    });
    expect(missingReason.status).toBe(400);
    expect((await responseJson<Problem>(missingReason)).errors?.[0]).toMatchObject({
      field: "reason",
      messageKey: "validation.block.reason.required",
    });

    const blocked = await responseJson<UserAccount>(
      await apiRequest("/api/v1/admin/users/carol/status", {
        method: "PUT",
        body: { status: "blocked", reason: "Repeated abuse" },
      }),
    );
    expect(blocked).toMatchObject({
      username: "carol",
      status: "blocked",
      blockedReason: "Repeated abuse",
    });
    expect(db.audit[0]).toMatchObject({
      actor: "admin",
      action: "user.blocked",
      targetType: "user",
      targetId: "carol",
      detail: { reason: "Repeated abuse" },
    });

    const unblocked = await responseJson<UserAccount>(
      await apiRequest("/api/v1/admin/users/carol/status", {
        method: "PUT",
        body: { status: "active" },
      }),
    );
    expect(unblocked.status).toBe("active");
    expect(unblocked.blockedReason).toBeUndefined();
    expect(db.audit[0]).toMatchObject({ action: "user.unblocked", targetId: "carol" });
  });

  it("filters the immutable audit trail by exact fields and instant bounds", async () => {
    const seedEntry = db.audit[0];
    if (!seedEntry) throw new Error("audit seed data is incomplete");
    setCurrentUser(MOCK_USERS.admin);

    const params = new URLSearchParams({
      actor: seedEntry.actor,
      action: seedEntry.action,
      targetType: seedEntry.targetType,
      targetId: seedEntry.targetId,
      from: seedEntry.createdAt,
      to: seedEntry.createdAt,
      page: "0",
      size: "1",
    });
    const matching = await responseJson<AuditPage>(
      await apiRequest(`/api/v1/admin/audit-log?${params}`),
    );
    expect(matching.items).toEqual([seedEntry]);

    const empty = await responseJson<AuditPage>(
      await apiRequest("/api/v1/admin/audit-log?actor=nobody&action=user.blocked"),
    );
    expect(empty).toMatchObject({ items: [], totalItems: 0, totalPages: 0 });
  });

  it("serves a zero-filled 30-day stats window with role checks and ETag revalidation", async () => {
    expect((await apiRequest("/api/v1/admin/stats")).status).toBe(401);
    setCurrentUser(MOCK_USERS.demo);
    expect((await apiRequest("/api/v1/admin/stats")).status).toBe(403);

    setCurrentUser(MOCK_USERS.moderator);
    const requestedOn = new Date().toISOString().slice(0, 10);
    const initialResponse = await apiRequest("/api/v1/admin/stats");
    const respondedOn = new Date().toISOString().slice(0, 10);
    expect(initialResponse.status).toBe(200);
    expect(initialResponse.headers.get("Cache-Control")).toBe("no-cache");
    const etag = initialResponse.headers.get("ETag");
    expect(etag).toBeTruthy();
    const stats = await responseJson<AdminStats>(initialResponse);
    const lastDay = stats.daily[stats.daily.length - 1]?.date;
    expect([requestedOn, respondedOn]).toContain(lastDay);
    const expectedDaily = Array.from({ length: 30 }, (_, index) => {
      const date = new Date(`${lastDay}T00:00:00.000Z`);
      date.setUTCDate(date.getUTCDate() - (29 - index));
      const day = date.toISOString().slice(0, 10);
      return {
        date: day,
        bookmarksCreated: db.bookmarks.filter((bookmark) =>
          bookmark.createdAt.startsWith(day),
        ).length,
        activeUsers: db.users.filter((user) => user.lastSeen.startsWith(day)).length,
      };
    });
    expect(stats.daily).toEqual(expectedDaily);
    expect(stats.topTags.length).toBeLessThanOrEqual(10);
    expect(stats.totals.openReports).toBe(
      db.reports.filter((report) => report.status === "open").length,
    );

    const notModified = await apiRequest("/api/v1/admin/stats", {
      headers: { "If-None-Match": etag ?? "" },
    });
    expect(notModified.status).toBe(304);

    const bookmark = db.bookmarks.find((candidate) => candidate.status === "active");
    if (!bookmark) throw new Error("bookmark seed data is incomplete");
    await apiRequest(`/api/v1/admin/bookmarks/${bookmark.id}/status`, {
      method: "PUT",
      body: { status: "hidden" },
    });
    const changed = await apiRequest("/api/v1/admin/stats", {
      headers: { "If-None-Match": etag ?? "" },
    });
    expect(changed.status).toBe(200);
    expect(changed.headers.get("ETag")).not.toBe(etag);
  });
});
