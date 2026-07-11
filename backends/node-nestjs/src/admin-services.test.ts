import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";

const { queryMock, transactionQueryMock, withTransactionMock, recordAuditMock, logEventMock, loggerInfoMock } =
  vi.hoisted(() => ({
    queryMock: vi.fn(),
    transactionQueryMock: vi.fn(),
    withTransactionMock: vi.fn(),
    recordAuditMock: vi.fn(),
    logEventMock: vi.fn(),
    loggerInfoMock: vi.fn(),
  }));

vi.mock("./db.js", () => ({
  pool: { query: queryMock },
  withTransaction: withTransactionMock,
}));
vi.mock("./audit.js", () => ({ recordAudit: recordAuditMock }));
vi.mock("./logging.js", () => ({
  logEvent: logEventMock,
  logger: { info: loggerInfoMock },
}));

import { AdminUsersService } from "./admin-users/admin-users.service.js";
import type { UserStatusBodyDto } from "./admin-users/user-status.dto.js";
import { AuditLogService } from "./audit-log/audit-log.service.js";
import type { Caller } from "./auth.js";
import { MetaService } from "./meta/meta.service.js";
import { BadRequestProblem, ConflictProblem, NotFoundProblem } from "./problems.js";
import { StatsService } from "./stats/stats.service.js";

const requestWith = (
  caller: Caller | null,
  query: Record<string, unknown> = {},
  headers: Record<string, string> = {},
) => ({ caller, query, headers }) as never;

const userRow = (overrides: Record<string, unknown> = {}) => ({
  username: "target",
  first_seen: new Date("2026-07-01T08:00:00.000Z"),
  last_seen: new Date("2026-07-11T08:00:00.000Z"),
  status: "active",
  blocked_reason: null,
  bookmark_count: 2,
  ...overrides,
});

const replyStub = () => {
  const reply = {
    statusCode: undefined as number | undefined,
    contentType: undefined as string | undefined,
    headers: {} as Record<string, string>,
    body: undefined as unknown,
    elapsedTime: 4,
    header(name: string, value: string) {
      this.headers[name] = value;
      return this;
    },
    code(status: number) {
      this.statusCode = status;
      return this;
    },
    type(value: string) {
      this.contentType = value;
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
  loggerInfoMock.mockReset();
  withTransactionMock.mockReset();
  withTransactionMock.mockImplementation(
    async (callback: (client: { query: typeof transactionQueryMock }) => Promise<unknown>) =>
      callback({ query: transactionQueryMock }),
  );
});

afterEach(() => {
  vi.useRealTimers();
});

describe("admin user accounts", () => {
  const admin = requestWith({ username: "admin", roles: ["admin"] });

  it("filters and pages the app-level directory with derived bookmark counts", async () => {
    queryMock
      .mockResolvedValueOnce({ rows: [userRow({ blocked_reason: "Policy" })] })
      .mockResolvedValueOnce({ rows: [{ count: 1 }] });

    const result = await new AdminUsersService().list(
      requestWith(
        { username: "admin", roles: ["admin"] },
        { q: String.raw`tar%_`, status: "blocked", page: "1", size: "10" },
      ),
    );

    expect(result).toMatchObject({ page: 1, size: 10, totalItems: 1, totalPages: 1 });
    expect(result.items[0]).toMatchObject({ username: "target", blockedReason: "Policy", bookmarkCount: 2 });
    const [sql, parameters] = queryMock.mock.calls[0] as [string, unknown[]];
    expect(sql).toContain("bookmark_count");
    expect(sql).toContain("limit 10 offset 10");
    expect(parameters).toEqual([String.raw`%tar\%\_%`, "blocked"]);
  });

  it("validates filters and masks missing accounts", async () => {
    const service = new AdminUsersService();
    await expect(
      service.list(requestWith({ username: "admin", roles: ["admin"] }, { status: "suspended" })),
    ).rejects.toBeInstanceOf(BadRequestProblem);

    queryMock.mockResolvedValueOnce({ rows: [] });
    await expect(service.get(admin, "missing")).rejects.toBeInstanceOf(NotFoundProblem);
  });

  it("refuses self-block before opening a transaction", async () => {
    await expect(
      new AdminUsersService().setStatus(admin, "admin", {
        status: "blocked",
        reason: "No",
      } as UserStatusBodyDto),
    ).rejects.toBeInstanceOf(ConflictProblem);
    expect(withTransactionMock).not.toHaveBeenCalled();
  });

  it("blocks and unblocks atomically, audits both, and keeps reasons out of diagnostic logs", async () => {
    const service = new AdminUsersService();
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [{ username: "target" }], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [], rowCount: 1 });
    queryMock.mockResolvedValueOnce({ rows: [userRow({ status: "blocked", blocked_reason: "Sensitive reason" })] });

    await expect(
      service.setStatus(admin, "target", { status: "blocked", reason: "Sensitive reason" } as UserStatusBodyDto),
    ).resolves.toMatchObject({ username: "target", status: "blocked", blockedReason: "Sensitive reason" });

    expect(recordAuditMock).toHaveBeenCalledWith(
      { query: transactionQueryMock },
      "admin",
      "user.blocked",
      "user",
      "target",
      { reason: "Sensitive reason" },
    );
    let logFields = logEventMock.mock.calls[0]?.[4] as Record<string, unknown>;
    expect(logFields).toEqual({ actor: "admin", resource_type: "user", resource_id: "target" });

    transactionQueryMock.mockReset();
    recordAuditMock.mockReset();
    logEventMock.mockReset();
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [{ username: "target" }], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [], rowCount: 1 });
    queryMock.mockResolvedValueOnce({ rows: [userRow()] });

    await expect(service.setStatus(admin, "target", { status: "active" } as UserStatusBodyDto)).resolves.toMatchObject({
      username: "target",
      status: "active",
    });
    expect(transactionQueryMock.mock.calls[1]?.[0]).toContain("blocked_reason = null");
    expect(recordAuditMock).toHaveBeenCalledWith(
      { query: transactionQueryMock },
      "admin",
      "user.unblocked",
      "user",
      "target",
    );
    logFields = logEventMock.mock.calls[0]?.[4] as Record<string, unknown>;
    expect(logFields).not.toHaveProperty("reason");
  });
});

describe("append-only audit browsing", () => {
  const admin = { username: "admin", roles: ["admin"] };

  it("parameterizes every supported filter and omits null detail on the wire", async () => {
    queryMock
      .mockResolvedValueOnce({
        rows: [
          {
            id: "audit-1",
            actor: "mod",
            action: "report.resolved",
            target_type: "report",
            target_id: "report-1",
            detail: null,
            created_at: new Date("2026-07-11T10:00:00Z"),
          },
        ],
      })
      .mockResolvedValueOnce({ rows: [{ count: 1 }] });

    const result = await new AuditLogService().list(
      requestWith(admin, {
        actor: "mod",
        action: "report.resolved",
        targetType: "report",
        targetId: "report-1",
        from: "2026-07-01T00:00:00Z",
        to: "2026-07-11T23:59:59Z",
        size: "5",
      }),
    );

    expect(result.items[0]).toEqual({
      id: "audit-1",
      actor: "mod",
      action: "report.resolved",
      targetType: "report",
      targetId: "report-1",
      createdAt: "2026-07-11T10:00:00.000Z",
    });
    expect(result).toMatchObject({ page: 0, size: 5, totalItems: 1, totalPages: 1 });
    const [sql, parameters] = queryMock.mock.calls[0] as [string, unknown[]];
    expect(sql).toContain("actor = $1");
    expect(sql).toContain("created_at >= $5");
    expect(sql).toContain("created_at <= $6");
    expect(parameters.slice(0, 4)).toEqual(["mod", "report.resolved", "report", "report-1"]);
    expect(parameters[4]).toEqual(new Date("2026-07-01T00:00:00Z"));
    expect(parameters[5]).toEqual(new Date("2026-07-11T23:59:59Z"));
  });

  it("rejects invalid RFC 3339 filter values before querying", async () => {
    await expect(
      new AuditLogService().list(requestWith(admin, { from: "definitely-not-a-date" })),
    ).rejects.toBeInstanceOf(BadRequestProblem);
    expect(queryMock).not.toHaveBeenCalled();
  });
});

describe("moderator statistics", () => {
  it("returns totals, exactly 30 UTC days with zero filling, top tags, and an ETag", async () => {
    vi.useFakeTimers();
    vi.setSystemTime(new Date("2026-07-11T16:30:00.000Z"));
    queryMock.mockImplementation(async (sql: string) => {
      if (sql.includes("from user_accounts where last_seen")) {
        return { rows: [{ day: "2026-07-11", count: 3 }] };
      }
      if (sql.includes("from bookmarks where created_at")) {
        return { rows: [{ day: "2026-07-10", count: 2 }] };
      }
      if (sql.includes("unnest(tags)")) return { rows: [{ tag: "node", count: 4 }] };
      if (sql.includes("from user_accounts")) return { rows: [{ count: 5 }] };
      if (sql.includes("visibility = 'public'")) return { rows: [{ count: 3 }] };
      if (sql.includes("status = 'hidden'")) return { rows: [{ count: 1 }] };
      if (sql.includes("from reports")) return { rows: [{ count: 2 }] };
      if (sql.includes("from bookmarks")) return { rows: [{ count: 7 }] };
      throw new Error(`Unexpected SQL: ${sql}`);
    });
    const reply = replyStub();

    await new StatsService().get(requestWith({ username: "mod", roles: ["moderator"] }), reply as never);

    const body = JSON.parse(reply.body as string) as {
      totals: Record<string, number>;
      daily: { date: string; bookmarksCreated: number; activeUsers: number }[];
      topTags: { tag: string; count: number }[];
    };
    expect(body.totals).toEqual({
      users: 5,
      bookmarks: 7,
      publicBookmarks: 3,
      hiddenBookmarks: 1,
      openReports: 2,
    });
    expect(body.daily).toHaveLength(30);
    expect(body.daily[0]).toEqual({ date: "2026-06-12", bookmarksCreated: 0, activeUsers: 0 });
    expect(body.daily.at(-2)).toEqual({ date: "2026-07-10", bookmarksCreated: 2, activeUsers: 0 });
    expect(body.daily.at(-1)).toEqual({ date: "2026-07-11", bookmarksCreated: 0, activeUsers: 3 });
    expect(body.topTags).toEqual([{ tag: "node", count: 4 }]);
    expect(reply.headers).toMatchObject({ etag: expect.any(String), "cache-control": "no-cache" });
  });
});

describe("meta and readiness boundaries", () => {
  it("echoes only application identity fields and reports liveness", async () => {
    const service = new MetaService();
    await expect(
      service.me(
        requestWith({
          username: "admin",
          roles: ["offline_access", "moderator", "admin"],
          name: "Admin",
          email: "admin@example.com",
        }),
      ),
    ).resolves.toEqual({
      username: "admin",
      roles: ["admin", "moderator"],
      name: "Admin",
      email: "admin@example.com",
    });
    await expect(service.healthz()).resolves.toEqual({ status: "up" });
  });

  it("logs readiness only on loss and recovery transitions", async () => {
    const service = new MetaService();
    const unavailable = Object.assign(new Error("down"), { code: "ECONNREFUSED" });
    queryMock.mockRejectedValueOnce(unavailable).mockRejectedValueOnce(unavailable).mockResolvedValueOnce({ rows: [] });

    const firstReply = replyStub();
    await expect(service.readyz(firstReply as never)).resolves.toEqual({ status: "unavailable" });
    expect(firstReply.statusCode).toBe(503);
    expect(logEventMock).toHaveBeenCalledTimes(1);
    expect(logEventMock).toHaveBeenCalledWith(
      "warn",
      "dependency_call_failed",
      "failure",
      "Readiness lost: database unreachable",
      expect.objectContaining({ dependency: "postgres", error_code: "ECONNREFUSED", duration_ms: expect.any(Number) }),
    );

    await service.readyz(replyStub() as never);
    expect(logEventMock).toHaveBeenCalledTimes(1);

    await expect(service.readyz(replyStub() as never)).resolves.toEqual({ status: "ready" });
    expect(loggerInfoMock).toHaveBeenCalledOnce();
    expect(loggerInfoMock).toHaveBeenCalledWith("Readiness restored: database reachable again");
  });
});
