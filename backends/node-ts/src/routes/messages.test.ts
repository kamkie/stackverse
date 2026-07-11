import Fastify from "fastify";
import { beforeEach, describe, expect, it, vi } from "vitest";

const mocks = vi.hoisted(() => ({
  query: vi.fn(),
  transaction: vi.fn(),
  recordAudit: vi.fn(),
  logEvent: vi.fn(),
  caller: { username: "admin", roles: ["admin"] },
}));

vi.mock("../db.js", () => ({
  pool: { query: mocks.query },
  withTransaction: mocks.transaction,
}));
vi.mock("../auth.js", () => ({ requireRole: vi.fn(() => mocks.caller) }));
vi.mock("../audit.js", () => ({ recordAudit: mocks.recordAudit }));
vi.mock("../logging.js", () => ({ logEvent: mocks.logEvent }));
vi.mock("../i18n.js", () => ({
  DEFAULT_LANGUAGE: "en",
  resolveLanguage: vi.fn(async () => "en"),
  messageBundle: vi.fn(async () => ({ greeting: "Hello" })),
}));

import { registerMessageRoutes } from "./messages.js";
import { ValidationProblem } from "../problems.js";

const row = {
  id: "11111111-1111-4111-8111-111111111111",
  key: "nav.home",
  language: "en",
  text: "Home",
  description: null,
  created_at: new Date("2026-01-01T00:00:00Z"),
  updated_at: new Date("2026-01-02T00:00:00Z"),
};

function app() {
  const instance = Fastify();
  registerMessageRoutes(instance);
  instance.setErrorHandler((error, _request, reply) => {
    const status = error instanceof ValidationProblem ? 400 : ((error as { status?: number }).status ?? 500);
    reply.code(status).send({ status, title: error.name, detail: error.message });
  });
  return instance;
}

describe("message route contract", () => {
  beforeEach(() => vi.clearAllMocks());

  it("lists deterministically with escaped search and pagination metadata", async () => {
    mocks.query.mockResolvedValueOnce({ rows: [row] }).mockResolvedValueOnce({ rows: [{ count: 1 }] });
    const server = app();
    const response = await server.inject("/api/v1/messages?q=100%25&page=0&size=20");
    expect(response.statusCode).toBe(200);
    expect(response.json()).toMatchObject({
      items: [{ key: "nav.home", language: "en" }],
      totalItems: 1,
      totalPages: 1,
    });
    expect(response.json().items[0]).not.toHaveProperty("description");
    expect(mocks.query.mock.calls[0]?.[0]).toContain("order by key, language");
    expect(mocks.query.mock.calls[0]?.[1]).toEqual(["%100\\%%"]);
    await server.close();
  });

  it("creates a message and audit entry atomically", async () => {
    const client = {
      query: vi
        .fn()
        .mockResolvedValueOnce({ rowCount: 0 })
        .mockResolvedValueOnce({ rows: [row] }),
    };
    mocks.transaction.mockImplementationOnce(async (work: (value: typeof client) => unknown) => work(client));
    const server = app();
    const response = await server.inject({
      method: "POST",
      url: "/api/v1/messages",
      payload: { key: "nav.home", language: "en", text: "Home" },
    });
    expect(response.statusCode).toBe(201);
    expect(response.headers.location).toBe(`/api/v1/messages/${row.id}`);
    expect(mocks.recordAudit).toHaveBeenCalledWith(client, "admin", "message.created", "message", row.id, {
      key: "nav.home",
      language: "en",
      text: "Home",
      description: null,
    });
    expect(mocks.logEvent).toHaveBeenCalledWith("info", "message_created", "success", "Message created", {
      actor: "admin",
      resource_type: "message",
      resource_id: row.id,
      message_key: "nav.home",
      language: "en",
    });
    await server.close();
  });

  it("maps both duplicate pre-checks and database races to conflict", async () => {
    const precheck = { query: vi.fn().mockResolvedValueOnce({ rowCount: 1 }) };
    mocks.transaction.mockImplementationOnce(async (work: (value: typeof precheck) => unknown) => work(precheck));
    let server = app();
    let response = await server.inject({
      method: "POST",
      url: "/api/v1/messages",
      payload: { key: "nav.home", language: "en", text: "Home" },
    });
    expect(response.statusCode).toBe(409);
    await server.close();

    const race = { query: vi.fn().mockResolvedValueOnce({ rowCount: 0 }).mockRejectedValueOnce({ code: "23505" }) };
    mocks.transaction.mockImplementationOnce(async (work: (value: typeof race) => unknown) => work(race));
    server = app();
    response = await server.inject({
      method: "POST",
      url: "/api/v1/messages",
      payload: { key: "nav.home", language: "en", text: "Home" },
    });
    expect(response.statusCode).toBe(409);
    await server.close();
  });

  it("validates all fields before persistence", async () => {
    const server = app();
    const response = await server.inject({
      method: "POST",
      url: "/api/v1/messages",
      payload: { key: "Bad Key", language: "EN", text: "" },
    });
    expect(response.statusCode).toBe(400);
    expect(mocks.transaction).not.toHaveBeenCalled();
    await server.close();
  });

  it("deletes with audit and returns 204, while missing rows return 404", async () => {
    const client = { query: vi.fn().mockResolvedValueOnce({ rows: [row] }) };
    mocks.transaction.mockImplementationOnce(async (work: (value: typeof client) => unknown) => work(client));
    let server = app();
    let response = await server.inject({ method: "DELETE", url: `/api/v1/messages/${row.id}` });
    expect(response.statusCode).toBe(204);
    expect(mocks.recordAudit).toHaveBeenCalledWith(
      client,
      "admin",
      "message.deleted",
      "message",
      row.id,
      expect.any(Object),
    );
    await server.close();

    const missing = { query: vi.fn().mockResolvedValueOnce({ rows: [] }) };
    mocks.transaction.mockImplementationOnce(async (work: (value: typeof missing) => unknown) => work(missing));
    server = app();
    response = await server.inject({ method: "DELETE", url: `/api/v1/messages/${row.id}` });
    expect(response.statusCode).toBe(404);
    await server.close();
  });
});
