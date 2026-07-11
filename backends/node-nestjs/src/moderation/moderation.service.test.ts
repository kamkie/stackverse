import { beforeEach, describe, expect, it, vi } from "vitest";

const { queryMock, transactionQueryMock, withTransactionMock, recordAuditMock, logEventMock } = vi.hoisted(() => ({
  queryMock: vi.fn(),
  transactionQueryMock: vi.fn(),
  withTransactionMock: vi.fn(),
  recordAuditMock: vi.fn(),
  logEventMock: vi.fn(),
}));

vi.mock("../db.js", () => ({
  pool: { query: queryMock },
  withTransaction: withTransactionMock,
}));
vi.mock("../audit.js", () => ({ recordAudit: recordAuditMock }));
vi.mock("../logging.js", () => ({ logEvent: logEventMock }));

import type { Caller } from "../auth.js";
import type { BookmarkRow } from "../bookmarks/bookmarks.service.js";
import { ConflictProblem, NotFoundProblem } from "../problems.js";
import type { BookmarkStatusBodyDto, ReportBodyDto, ResolutionBodyDto, ReportStatus } from "./moderation.dto.js";
import { ModerationService } from "./moderation.service.js";

const BOOKMARK_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
const REPORT_ID = "1f8fad5b-d9cb-469f-a165-70867728950e";
const SIBLING_ID = "2f8fad5b-d9cb-469f-a165-70867728950e";

interface ReportRow {
  id: string;
  bookmark_id: string;
  reporter: string;
  reason: string;
  comment: string | null;
  status: ReportStatus;
  resolved_by: string | null;
  resolved_at: Date | null;
  resolution_note: string | null;
  created_at: Date;
}

const reportRow = (overrides: Partial<ReportRow> = {}): ReportRow => ({
  id: REPORT_ID,
  bookmark_id: BOOKMARK_ID,
  reporter: "demo",
  reason: "spam",
  comment: null,
  status: "open",
  resolved_by: null,
  resolved_at: null,
  resolution_note: null,
  created_at: new Date("2026-07-10T12:00:00.000Z"),
  ...overrides,
});

const bookmarkRow = (overrides: Partial<BookmarkRow> = {}): BookmarkRow => ({
  id: BOOKMARK_ID,
  owner: "owner",
  url: "https://example.com",
  title: "Example",
  notes: null,
  tags: ["node"],
  visibility: "public",
  status: "active",
  created_at: new Date("2026-07-09T12:00:00.000Z"),
  updated_at: new Date("2026-07-09T12:00:00.000Z"),
  ...overrides,
});

const requestWith = (caller: Caller | null, query: Record<string, unknown> = {}) =>
  ({ caller, query, headers: {} }) as never;

const reportInput = (overrides: Partial<ReportBodyDto> = {}): ReportBodyDto =>
  ({ reason: "spam", comment: null, ...overrides }) as ReportBodyDto;

const resolutionInput = (resolution: ReportStatus, note: string | null = null): ResolutionBodyDto =>
  ({ resolution, note }) as ResolutionBodyDto;

const replyStub = () => {
  const reply = {
    statusCode: undefined as number | undefined,
    body: undefined as unknown,
    code(status: number) {
      this.statusCode = status;
      return this;
    },
    send(body?: unknown) {
      this.body = body;
      return this;
    },
  };
  return reply;
};

beforeEach(() => {
  queryMock.mockReset();
  transactionQueryMock.mockReset();
  recordAuditMock.mockReset();
  logEventMock.mockReset();
  withTransactionMock.mockReset();
  withTransactionMock.mockImplementation(
    async (callback: (client: { query: typeof transactionQueryMock }) => Promise<unknown>) =>
      callback({ query: transactionQueryMock }),
  );
});

describe("reporter workflow", () => {
  const reporter = requestWith({ username: "demo", roles: [] });

  it("creates an open report only after locking and rechecking a public active bookmark", async () => {
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [{ visibility: "public", status: "active" }], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [], rowCount: 0 })
      .mockResolvedValueOnce({ rows: [reportRow({ comment: "Do not log this comment" })], rowCount: 1 });
    const reply = replyStub();

    await new ModerationService().reportBookmark(
      reporter,
      reply as never,
      BOOKMARK_ID,
      reportInput({ comment: "Do not log this comment" }),
    );

    expect(transactionQueryMock.mock.calls[0]?.[0]).toContain("bookmarks where id = $1 for update");
    expect(transactionQueryMock.mock.calls[1]?.[0]).toContain("status = 'open'");
    expect(reply.statusCode).toBe(201);
    expect(reply.body).toMatchObject({ id: REPORT_ID, bookmarkId: BOOKMARK_ID, reporter: "demo", status: "open" });
    const fields = logEventMock.mock.calls[0]?.[4] as Record<string, unknown>;
    expect(fields).toMatchObject({ actor: "demo", resource_id: REPORT_ID, bookmark_id: BOOKMARK_ID, reason: "spam" });
    expect(fields).not.toHaveProperty("comment");
    expect(recordAuditMock).not.toHaveBeenCalled();
  });

  it("masks non-reportable bookmarks and maps duplicate prechecks and races to 409", async () => {
    const service = new ModerationService();
    transactionQueryMock.mockResolvedValueOnce({ rows: [{ visibility: "private", status: "active" }], rowCount: 1 });
    await expect(
      service.reportBookmark(reporter, replyStub() as never, BOOKMARK_ID, reportInput()),
    ).rejects.toBeInstanceOf(NotFoundProblem);

    transactionQueryMock.mockReset();
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [{ visibility: "public", status: "active" }], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [{ exists: 1 }], rowCount: 1 });
    await expect(
      service.reportBookmark(reporter, replyStub() as never, BOOKMARK_ID, reportInput()),
    ).rejects.toBeInstanceOf(ConflictProblem);

    transactionQueryMock.mockReset();
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [{ visibility: "public", status: "active" }], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [], rowCount: 0 })
      .mockRejectedValueOnce(Object.assign(new Error("duplicate"), { code: "23505" }));
    await expect(
      service.reportBookmark(reporter, replyStub() as never, BOOKMARK_ID, reportInput()),
    ).rejects.toBeInstanceOf(ConflictProblem);
  });

  it("lists only the caller's reports with the requested status and pagination", async () => {
    queryMock
      .mockResolvedValueOnce({ rows: [reportRow({ status: "dismissed", resolved_by: "mod" })] })
      .mockResolvedValueOnce({ rows: [{ count: 1 }] });

    const result = await new ModerationService().listMyReports(
      requestWith({ username: "demo", roles: [] }, { status: "dismissed", page: "1", size: "5" }),
    );

    expect(result).toMatchObject({ page: 1, size: 5, totalItems: 1, totalPages: 1 });
    expect(result.items[0]).toMatchObject({ reporter: "demo", status: "dismissed", resolvedBy: "mod" });
    expect(queryMock.mock.calls[0]?.[1]).toEqual(["demo", "dismissed"]);
    expect(queryMock.mock.calls[0]?.[0]).toContain("limit 5 offset 5");
  });

  it("updates only the caller's own open report without creating an audit entry", async () => {
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [reportRow()], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [reportRow({ reason: "broken-link", comment: "Updated" })], rowCount: 1 });

    await expect(
      new ModerationService().updateMyReport(
        reporter,
        REPORT_ID,
        reportInput({ reason: "broken-link", comment: "Updated" }),
      ),
    ).resolves.toMatchObject({ id: REPORT_ID, reason: "broken-link", comment: "Updated" });

    expect(logEventMock).toHaveBeenCalledWith(
      "info",
      "report_updated",
      "success",
      "Report updated by its reporter",
      expect.objectContaining({ actor: "demo", resource_id: REPORT_ID, reason: "broken-link" }),
    );
    expect(recordAuditMock).not.toHaveBeenCalled();
  });

  it("masks another reporter's row and rejects edits after resolution", async () => {
    const service = new ModerationService();
    transactionQueryMock.mockResolvedValueOnce({ rows: [reportRow({ reporter: "another" })], rowCount: 1 });
    await expect(service.updateMyReport(reporter, REPORT_ID, reportInput())).rejects.toBeInstanceOf(NotFoundProblem);

    transactionQueryMock.mockReset();
    transactionQueryMock.mockResolvedValueOnce({ rows: [reportRow({ status: "dismissed" })], rowCount: 1 });
    await expect(service.updateMyReport(reporter, REPORT_ID, reportInput())).rejects.toBeInstanceOf(ConflictProblem);
  });

  it("withdraws an owned open report with 204 and diagnostic logging only", async () => {
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [reportRow()], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [], rowCount: 1 });
    const reply = replyStub();

    await new ModerationService().withdrawReport(reporter, reply as never, REPORT_ID);

    expect(transactionQueryMock.mock.calls[1]).toEqual(["delete from reports where id = $1", [REPORT_ID]]);
    expect(reply.statusCode).toBe(204);
    expect(recordAuditMock).not.toHaveBeenCalled();
    expect(logEventMock).toHaveBeenCalledWith(
      "info",
      "report_withdrawn",
      "success",
      "Report withdrawn by its reporter",
      expect.objectContaining({ actor: "demo", resource_id: REPORT_ID }),
    );
  });
});

describe("moderator workflow", () => {
  const moderator = requestWith({ username: "mod", roles: ["moderator"] });

  it("lists the open queue oldest-first by default", async () => {
    queryMock.mockResolvedValueOnce({ rows: [reportRow()] }).mockResolvedValueOnce({ rows: [{ count: 1 }] });

    const result = await new ModerationService().listAdminReports(moderator);

    expect(result).toMatchObject({ page: 0, size: 20, totalItems: 1 });
    expect(queryMock.mock.calls[0]?.[1]).toEqual(["open"]);
    expect(queryMock.mock.calls[0]?.[0]).toContain("order by created_at asc, id asc");
  });

  it("locks bookmark first, hides once, and action-resolves every open sibling atomically", async () => {
    const resolvedAt = new Date("2026-07-11T12:00:00.000Z");
    const resolved = reportRow({
      status: "actioned",
      resolved_by: "mod",
      resolved_at: resolvedAt,
      resolution_note: "Confirmed",
    });
    const sibling = reportRow({ id: SIBLING_ID, reporter: "another" });
    const resolvedSibling = reportRow({
      id: SIBLING_ID,
      reporter: "another",
      status: "actioned",
      resolved_by: "mod",
      resolved_at: resolvedAt,
      resolution_note: "Confirmed",
    });
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [{ bookmark_id: BOOKMARK_ID }], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [{ id: BOOKMARK_ID }], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [reportRow()], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [resolved], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [bookmarkRow()], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [sibling], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [resolvedSibling], rowCount: 1 });

    await expect(
      new ModerationService().resolveReport(moderator, REPORT_ID, resolutionInput("actioned", "Confirmed")),
    ).resolves.toMatchObject({ id: REPORT_ID, status: "actioned", resolvedBy: "mod", resolutionNote: "Confirmed" });

    const sql = transactionQueryMock.mock.calls.map((call) => call[0] as string);
    expect(sql[0]).toContain("select bookmark_id from reports");
    expect(sql[1]).toContain("bookmarks where id = $1 for update");
    expect(sql[2]).toContain("reports where id = $1 for update");
    expect(sql[5]).toContain("set status = 'hidden'");
    expect(sql[6]).toContain("status = 'open' and id <> $2");

    expect(recordAuditMock).toHaveBeenCalledTimes(3);
    expect(recordAuditMock.mock.calls.map((call) => call[2])).toEqual([
      "report.resolved",
      "bookmark.status-changed",
      "report.resolved",
    ]);
    expect(recordAuditMock.mock.calls[0]?.[5]).toMatchObject({ resolution: "actioned", autoResolved: false });
    expect(recordAuditMock.mock.calls[2]?.[5]).toMatchObject({ resolution: "actioned", autoResolved: true });
    expect(logEventMock.mock.calls.map((call) => call[1])).toEqual([
      "report_resolved",
      "bookmark_status_changed",
      "report_resolved",
    ]);
  });

  it("reopens a report by clearing resolution fields and ignoring the supplied note", async () => {
    const previouslyResolved = reportRow({
      status: "dismissed",
      resolved_by: "mod",
      resolved_at: new Date("2026-07-10T13:00:00Z"),
      resolution_note: "Old note",
    });
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [previouslyResolved], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [], rowCount: 0 })
      .mockResolvedValueOnce({ rows: [reportRow()], rowCount: 1 });

    const result = await new ModerationService().resolveReport(
      moderator,
      REPORT_ID,
      resolutionInput("open", "This note must be ignored"),
    );

    expect(result).toMatchObject({ id: REPORT_ID, status: "open" });
    expect(result).not.toHaveProperty("resolvedBy");
    expect(result).not.toHaveProperty("resolutionNote");
    expect(transactionQueryMock.mock.calls[2]?.[1]).toEqual([REPORT_ID]);
    expect(recordAuditMock).toHaveBeenCalledWith(
      { query: transactionQueryMock },
      "mod",
      "report.reopened",
      "report",
      REPORT_ID,
      { bookmarkId: BOOKMARK_ID },
    );
    expect(logEventMock).toHaveBeenCalledWith(
      "info",
      "report_reopened",
      "success",
      "Report re-opened",
      expect.objectContaining({ actor: "mod", resource_id: REPORT_ID }),
    );
  });

  it("maps both reopen conflicts and unique-index races to 409", async () => {
    const service = new ModerationService();
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [reportRow({ status: "dismissed" })], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [{ exists: 1 }], rowCount: 1 });
    await expect(service.resolveReport(moderator, REPORT_ID, resolutionInput("open"))).rejects.toBeInstanceOf(
      ConflictProblem,
    );

    transactionQueryMock.mockReset();
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [reportRow({ status: "dismissed" })], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [], rowCount: 0 })
      .mockRejectedValueOnce(Object.assign(new Error("duplicate"), { code: "23505" }));
    await expect(service.resolveReport(moderator, REPORT_ID, resolutionInput("open"))).rejects.toBeInstanceOf(
      ConflictProblem,
    );
  });

  it("changes bookmark status without changing visibility and records the audit detail", async () => {
    const current = bookmarkRow({ visibility: "public", status: "hidden" });
    const restored = bookmarkRow({
      visibility: "public",
      status: "active",
      updated_at: new Date("2026-07-11T12:00:00Z"),
    });
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [current], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [restored], rowCount: 1 });

    const result = await new ModerationService().setBookmarkStatus(moderator, BOOKMARK_ID, {
      status: "active",
      note: "Reviewed",
    } as BookmarkStatusBodyDto);

    expect(result).toMatchObject({ id: BOOKMARK_ID, visibility: "public", status: "active" });
    expect(transactionQueryMock.mock.calls[1]?.[0]).not.toContain("visibility");
    expect(recordAuditMock).toHaveBeenCalledWith(
      { query: transactionQueryMock },
      "mod",
      "bookmark.status-changed",
      "bookmark",
      BOOKMARK_ID,
      { from: "hidden", to: "active", note: "Reviewed" },
    );
    expect(logEventMock).toHaveBeenCalledWith(
      "info",
      "bookmark_status_changed",
      "success",
      "Bookmark moderation status changed",
      expect.objectContaining({ actor: "mod", from: "hidden", to: "active" }),
    );
  });
});
