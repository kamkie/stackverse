import Fastify from "fastify";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  query: vi.fn(),
  transaction: vi.fn(),
  recordAudit: vi.fn(),
  logEvent: vi.fn(),
  caller: { username: "moderator", roles: ["moderator"] },
}));
vi.mock("../db.js", () => ({ pool: { query: mocks.query }, withTransaction: mocks.transaction }));
vi.mock("../auth.js", () => ({ requireCaller: vi.fn(() => mocks.caller), requireRole: vi.fn(() => mocks.caller) }));
vi.mock("../audit.js", () => ({ recordAudit: mocks.recordAudit }));
vi.mock("../logging.js", () => ({ logEvent: mocks.logEvent }));

import { registerModerationRoutes } from "./moderation.js";
import { ValidationProblem } from "../problems.js";

const id = "22222222-2222-4222-8222-222222222222";
const bookmark = {
  id,
  owner: "alice",
  url: "https://example.test",
  title: "Example",
  notes: null,
  tags: ["test"],
  visibility: "public",
  status: "active",
  created_at: new Date("2026-01-01Z"),
  updated_at: new Date("2026-01-01Z"),
};

function app() {
  const server = Fastify();
  registerModerationRoutes(server);
  server.setErrorHandler((error, _request, reply) => {
    const status = error instanceof ValidationProblem ? 400 : ((error as { status?: number }).status ?? 500);
    reply.code(status).send({ status, detail: error.message });
  });
  return server;
}

describe("moderation state boundaries", () => {
  beforeEach(() => vi.clearAllMocks());

  it("defaults the moderation queue to open reports and oldest-first order", async () => {
    mocks.query.mockResolvedValueOnce({ rows: [] }).mockResolvedValueOnce({ rows: [{ count: 0 }] });
    const server = app();
    const response = await server.inject("/api/v1/admin/reports");
    expect(response.statusCode).toBe(200);
    expect(response.json()).toEqual({ items: [], page: 0, size: 20, totalItems: 0, totalPages: 0 });
    expect(mocks.query.mock.calls[0]).toEqual([expect.stringContaining("order by created_at asc, id asc"), ["open"]]);
    await server.close();
  });

  it("rejects invalid bookmark moderation state before opening a transaction", async () => {
    const server = app();
    const response = await server.inject({
      method: "PUT",
      url: `/api/v1/admin/bookmarks/${id}/status`,
      payload: { status: "deleted" },
    });
    expect(response.statusCode).toBe(400);
    expect(mocks.transaction).not.toHaveBeenCalled();
    await server.close();
  });

  it("changes only moderation status and records the transition atomically", async () => {
    const updated = { ...bookmark, status: "hidden", updated_at: new Date("2026-01-02Z") };
    const client = {
      query: vi
        .fn()
        .mockResolvedValueOnce({ rows: [bookmark] })
        .mockResolvedValueOnce({ rows: [updated] }),
    };
    mocks.transaction.mockImplementationOnce(async (work: (value: typeof client) => unknown) => work(client));
    const server = app();
    const response = await server.inject({
      method: "PUT",
      url: `/api/v1/admin/bookmarks/${id}/status`,
      payload: { status: "hidden", note: "policy" },
    });
    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({ id, visibility: "public", status: "hidden" });
    expect(client.query.mock.calls[1]?.[0]).toContain("set status = $2");
    expect(mocks.recordAudit).toHaveBeenCalledWith(client, "moderator", "bookmark.status-changed", "bookmark", id, {
      from: "active",
      to: "hidden",
      note: "policy",
    });
    expect(mocks.logEvent).toHaveBeenCalledWith(
      "info",
      "bookmark_status_changed",
      "success",
      expect.any(String),
      expect.objectContaining({ from: "active", to: "hidden" }),
    );
    await server.close();
  });

  it("returns not found without audit when the locked bookmark is absent", async () => {
    const client = { query: vi.fn().mockResolvedValueOnce({ rows: [] }) };
    mocks.transaction.mockImplementationOnce(async (work: (value: typeof client) => unknown) => work(client));
    const server = app();
    const response = await server.inject({
      method: "PUT",
      url: `/api/v1/admin/bookmarks/${id}/status`,
      payload: { status: "hidden" },
    });
    expect(response.statusCode).toBe(404);
    expect(mocks.recordAudit).not.toHaveBeenCalled();
    await server.close();
  });
});
