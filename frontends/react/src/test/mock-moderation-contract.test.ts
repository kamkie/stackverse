import { describe, expect, it } from "vitest";
import type { components } from "../api/schema";
import { db } from "../mocks/db";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { apiRequest, responseJson } from "./http";

type Bookmark = components["schemas"]["Bookmark"];
type Problem = components["schemas"]["Problem"];
type Report = components["schemas"]["Report"];
type ReportPage = components["schemas"]["ReportPage"];

describe("moderation mock contract", () => {
  it("validates report visibility and input before enforcing one open report per caller", async () => {
    const reportable = db.bookmarks.find(
      (bookmark) =>
        bookmark.owner === "carol" &&
        bookmark.visibility === "public" &&
        bookmark.status === "active" &&
        !db.reports.some((report) => report.bookmarkId === bookmark.id && report.reporter === "demo"),
    );
    const privateBookmark = db.bookmarks.find(
      (bookmark) => bookmark.owner === "demo" && bookmark.visibility === "private",
    );
    const hiddenBookmark = db.bookmarks.find((bookmark) => bookmark.status === "hidden");
    if (!reportable || !privateBookmark || !hiddenBookmark) {
      throw new Error("bookmark seed data is incomplete");
    }

    expect(
      (
        await apiRequest(`/api/v1/bookmarks/${reportable.id}/reports`, {
          method: "POST",
          body: { reason: "spam" },
        })
      ).status,
    ).toBe(401);

    setCurrentUser(MOCK_USERS.demo);
    for (const bookmark of [privateBookmark, hiddenBookmark]) {
      expect(
        (
          await apiRequest(`/api/v1/bookmarks/${bookmark.id}/reports`, {
            method: "POST",
            body: { reason: "spam" },
          })
        ).status,
      ).toBe(404);
    }

    const invalid = await apiRequest(`/api/v1/bookmarks/${reportable.id}/reports`, {
      method: "POST",
      headers: { "Accept-Language": "pl" },
      body: { reason: "not-a-reason", comment: "x".repeat(1001) },
    });
    expect(invalid.status).toBe(400);
    expect((await responseJson<Problem>(invalid)).errors?.map((error) => error.field)).toEqual([
      "reason",
      "comment",
    ]);

    const createdResponse = await apiRequest(
      `/api/v1/bookmarks/${reportable.id}/reports`,
      {
        method: "POST",
        body: { reason: "broken-link", comment: "The link no longer works." },
      },
    );
    expect(createdResponse.status).toBe(201);
    const created = await responseJson<Report>(createdResponse);
    expect(created).toMatchObject({
      bookmarkId: reportable.id,
      reporter: "demo",
      status: "open",
    });

    const duplicate = await apiRequest(`/api/v1/bookmarks/${reportable.id}/reports`, {
      method: "POST",
      body: { reason: "other" },
    });
    expect(duplicate.status).toBe(409);

    const ownReports = await responseJson<ReportPage>(
      await apiRequest("/api/v1/reports?status=open&page=0&size=1"),
    );
    expect(ownReports.items).toHaveLength(1);
    expect(ownReports.totalItems).toBeGreaterThan(1);
    expect(ownReports.items[0]?.reporter).toBe("demo");
  });

  it("masks another reporter's filing and rejects edits or withdrawal after resolution", async () => {
    const report = db.reports.find((candidate) => candidate.reporter === "demo");
    if (!report) throw new Error("report seed data is incomplete");

    expect(
      (
        await apiRequest(`/api/v1/reports/${report.id}`, {
          method: "PUT",
          body: { reason: "other" },
        })
      ).status,
    ).toBe(401);
    expect(
      (
        await apiRequest(`/api/v1/reports/${report.id}`, {
          method: "DELETE",
        })
      ).status,
    ).toBe(401);

    setCurrentUser(MOCK_USERS.moderator);
    expect(
      (
        await apiRequest(`/api/v1/reports/${report.id}`, {
          method: "PUT",
          body: { reason: "other" },
        })
      ).status,
    ).toBe(404);
    expect(
      (
        await apiRequest(`/api/v1/reports/${report.id}`, {
          method: "DELETE",
        })
      ).status,
    ).toBe(404);

    setCurrentUser(MOCK_USERS.demo);
    const invalid = await apiRequest(`/api/v1/reports/${report.id}`, {
      method: "PUT",
      body: { reason: "invalid", comment: "x".repeat(1001) },
    });
    expect(invalid.status).toBe(400);
    expect((await responseJson<Problem>(invalid)).errors).toHaveLength(2);

    const updatedResponse = await apiRequest(`/api/v1/reports/${report.id}`, {
      method: "PUT",
      body: { reason: "offensive" },
    });
    expect(updatedResponse.status).toBe(200);
    expect(await responseJson<Report>(updatedResponse)).toMatchObject({
      reason: "offensive",
      status: "open",
    });
    expect(report.comment).toBeUndefined();

    report.status = "dismissed";
    expect(
      (
        await apiRequest(`/api/v1/reports/${report.id}`, {
          method: "PUT",
          body: { reason: "spam" },
        })
      ).status,
    ).toBe(409);
    expect(
      (
        await apiRequest(`/api/v1/reports/${report.id}`, {
          method: "DELETE",
        })
      ).status,
    ).toBe(409);
  });

  it("actioning a report hides the bookmark, cascades siblings, audits, and can be reopened", async () => {
    const target = db.reports.find((report) => report.status === "open");
    if (!target) throw new Error("report seed data is incomplete");
    const bookmark = db.bookmarks.find((candidate) => candidate.id === target.bookmarkId);
    if (!bookmark) throw new Error("reported bookmark seed data is incomplete");

    expect((await apiRequest("/api/v1/admin/reports")).status).toBe(401);
    setCurrentUser(MOCK_USERS.demo);
    expect((await apiRequest("/api/v1/admin/reports")).status).toBe(403);

    setCurrentUser(MOCK_USERS.moderator);
    const expectedIds = db.reports
      .filter((report) => report.status === "open")
      .sort((a, b) => a.createdAt.localeCompare(b.createdAt))
      .map((report) => report.id);
    // The seed is already oldest-first. Reverse persistence order so this
    // assertion fails if the handler ever drops its required ordering.
    db.reports.reverse();
    const queue = await responseJson<ReportPage>(
      await apiRequest("/api/v1/admin/reports?status=open"),
    );
    expect(queue.items.map((report) => report.id)).toEqual(expectedIds);

    const actionedResponse = await apiRequest(`/api/v1/admin/reports/${target.id}`, {
      method: "PUT",
      body: { resolution: "actioned", note: "Confirmed abuse" },
    });
    expect(actionedResponse.status).toBe(200);
    expect(await responseJson<Report>(actionedResponse)).toMatchObject({
      status: "actioned",
      resolvedBy: "moderator",
      resolutionNote: "Confirmed abuse",
    });
    expect(bookmark.status).toBe("hidden");
    expect(
      db.reports
        .filter((report) => report.bookmarkId === bookmark.id)
        .every((report) => report.status === "actioned" && report.resolvedBy === "moderator"),
    ).toBe(true);
    expect(
      db.audit.filter(
        (entry) =>
          entry.action === "report.resolved" || entry.action === "bookmark.status-changed",
      ),
    ).toHaveLength(3);
    expect((await apiRequest(`/api/v1/bookmarks/${bookmark.id}`)).status).toBe(404);

    const reopenedResponse = await apiRequest(`/api/v1/admin/reports/${target.id}`, {
      method: "PUT",
      body: { resolution: "open", note: "ignored while reopening" },
    });
    const reopened = await responseJson<Report>(reopenedResponse);
    expect(reopened).toMatchObject({ status: "open" });
    expect(reopened.resolvedBy).toBeUndefined();
    expect(reopened.resolvedAt).toBeUndefined();
    expect(reopened.resolutionNote).toBeUndefined();
    expect(bookmark.status).toBe("hidden");
    expect(db.audit[0]).toMatchObject({ action: "report.reopened", targetId: target.id });
  });

  it("enforces moderator role boundaries on explicit hide and restore operations", async () => {
    const bookmark = db.bookmarks.find((candidate) => candidate.visibility === "public");
    if (!bookmark) throw new Error("bookmark seed data is incomplete");

    expect(
      (
        await apiRequest(`/api/v1/admin/bookmarks/${bookmark.id}/status`, {
          method: "PUT",
          body: { status: "hidden" },
        })
      ).status,
    ).toBe(401);
    setCurrentUser(MOCK_USERS.demo);
    expect(
      (
        await apiRequest(`/api/v1/admin/bookmarks/${bookmark.id}/status`, {
          method: "PUT",
          body: { status: "hidden" },
        })
      ).status,
    ).toBe(403);

    setCurrentUser(MOCK_USERS.moderator);
    expect(
      (
        await apiRequest("/api/v1/admin/bookmarks/00000000-0000-4000-8000-999999999999/status", {
          method: "PUT",
          body: { status: "hidden" },
        })
      ).status,
    ).toBe(404);

    const hidden = await responseJson<Bookmark>(
      await apiRequest(`/api/v1/admin/bookmarks/${bookmark.id}/status`, {
        method: "PUT",
        body: { status: "hidden", note: "Manual review" },
      }),
    );
    expect(hidden.status).toBe("hidden");
    const restored = await responseJson<Bookmark>(
      await apiRequest(`/api/v1/admin/bookmarks/${bookmark.id}/status`, {
        method: "PUT",
        body: { status: "active" },
      }),
    );
    expect(restored).toMatchObject({ status: "active", visibility: bookmark.visibility });
    expect(db.audit.slice(0, 2).map((entry) => entry.action)).toEqual([
      "bookmark.status-changed",
      "bookmark.status-changed",
    ]);
  });
});
