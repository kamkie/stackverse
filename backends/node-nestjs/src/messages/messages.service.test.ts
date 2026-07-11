import { beforeEach, describe, expect, it, vi } from "vitest";

const {
  queryMock,
  transactionQueryMock,
  withTransactionMock,
  recordAuditMock,
  logEventMock,
  resolveRequestLanguageMock,
  messageBundleMock,
} = vi.hoisted(() => ({
  queryMock: vi.fn(),
  transactionQueryMock: vi.fn(),
  withTransactionMock: vi.fn(),
  recordAuditMock: vi.fn(),
  logEventMock: vi.fn(),
  resolveRequestLanguageMock: vi.fn(),
  messageBundleMock: vi.fn(),
}));

vi.mock("../db.js", () => ({
  pool: { query: queryMock },
  withTransaction: withTransactionMock,
}));
vi.mock("../audit.js", () => ({ recordAudit: recordAuditMock }));
vi.mock("../logging.js", () => ({ logEvent: logEventMock }));
vi.mock("../i18n.js", () => ({
  DEFAULT_LANGUAGE: "en",
  resolveRequestLanguage: resolveRequestLanguageMock,
  messageBundle: messageBundleMock,
}));

import type { Caller } from "../auth.js";
import { ConflictProblem, NotFoundProblem } from "../problems.js";
import type { MessageBodyDto } from "./message.dto.js";
import { MessagesService } from "./messages.service.js";

const MESSAGE_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";

interface MessageRow {
  id: string;
  key: string;
  language: string;
  text: string;
  description: string | null;
  created_at: Date;
  updated_at: Date;
}

const messageRow = (overrides: Partial<MessageRow> = {}): MessageRow => ({
  id: MESSAGE_ID,
  key: "ui.save",
  language: "en",
  text: "Save",
  description: null,
  created_at: new Date("2026-07-10T12:00:00.000Z"),
  updated_at: new Date("2026-07-10T12:00:00.000Z"),
  ...overrides,
});

const requestWith = (
  caller: Caller | null,
  query: Record<string, unknown> = {},
  headers: Record<string, string> = {},
) => ({ caller, query, headers }) as never;

const input = (overrides: Partial<MessageBodyDto> = {}): MessageBodyDto =>
  ({ key: "ui.save", language: "en", text: "Save", description: null, ...overrides }) as MessageBodyDto;

const replyStub = () => {
  const reply = {
    statusCode: undefined as number | undefined,
    contentType: undefined as string | undefined,
    headers: {} as Record<string, string>,
    body: undefined as unknown,
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
  resolveRequestLanguageMock.mockReset();
  messageBundleMock.mockReset();
  withTransactionMock.mockReset();
  withTransactionMock.mockImplementation(
    async (callback: (client: { query: typeof transactionQueryMock }) => Promise<unknown>) =>
      callback({ query: transactionQueryMock }),
  );
});

describe("public message reads", () => {
  it("parameterizes filters and sends a deterministic paged ETag response", async () => {
    queryMock
      .mockResolvedValueOnce({ rows: [messageRow({ description: "Button label" })] })
      .mockResolvedValueOnce({ rows: [{ count: 1 }] });
    const reply = replyStub();

    await new MessagesService().list(
      requestWith(null, { key: "ui.save", language: "en", q: "50%_", page: "1", size: "5" }),
      reply as never,
    );

    expect(reply.headers).toMatchObject({ etag: expect.any(String), "cache-control": "no-cache" });
    expect(reply.contentType).toBe("application/json; charset=utf-8");
    expect(JSON.parse(reply.body as string)).toMatchObject({
      items: [{ id: MESSAGE_ID, key: "ui.save", description: "Button label" }],
      page: 1,
      size: 5,
      totalItems: 1,
      totalPages: 1,
    });
    const [sql, parameters] = queryMock.mock.calls[0] as [string, unknown[]];
    expect(sql).toContain("key = $1");
    expect(sql).toContain("language = $2");
    expect(sql).toContain("limit 5 offset 5");
    expect(parameters).toEqual(["ui.save", "en", String.raw`%50\%\_%`]);
  });

  it("resolves bundle language, reports it, and delegates fallback assembly", async () => {
    resolveRequestLanguageMock.mockResolvedValueOnce("pl");
    messageBundleMock.mockResolvedValueOnce({ "ui.save": "Zapisz" });
    const reply = replyStub();

    await new MessagesService().bundle(requestWith(null, { lang: "pl" }, { "accept-language": "en" }), reply as never);

    expect(resolveRequestLanguageMock).toHaveBeenCalledWith({ lang: "pl" }, "en");
    expect(messageBundleMock).toHaveBeenCalledWith("pl");
    expect(reply.headers["content-language"]).toBe("pl");
    expect(JSON.parse(reply.body as string)).toEqual({ language: "pl", messages: { "ui.save": "Zapisz" } });
  });

  it("returns one message with ETag and masks a missing id", async () => {
    const service = new MessagesService();
    queryMock.mockResolvedValueOnce({ rows: [messageRow()] });
    const reply = replyStub();
    await service.get(requestWith(null), reply as never, MESSAGE_ID);
    expect(JSON.parse(reply.body as string)).toMatchObject({ id: MESSAGE_ID, key: "ui.save" });

    queryMock.mockResolvedValueOnce({ rows: [] });
    await expect(service.get(requestWith(null), replyStub() as never, MESSAGE_ID)).rejects.toBeInstanceOf(
      NotFoundProblem,
    );
  });
});

describe("audited message mutations", () => {
  const admin = requestWith({ username: "admin", roles: ["admin"] });

  it("creates and audits in one transaction, returns Location, and never logs message text", async () => {
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [], rowCount: 0 })
      .mockResolvedValueOnce({ rows: [messageRow({ text: "Private-ish translation text" })], rowCount: 1 });
    const reply = replyStub();

    await new MessagesService().create(
      admin,
      reply as never,
      input({ text: "Private-ish translation text", description: "Translator context" }),
    );

    expect(recordAuditMock).toHaveBeenCalledWith(
      { query: transactionQueryMock },
      "admin",
      "message.created",
      "message",
      MESSAGE_ID,
      {
        key: "ui.save",
        language: "en",
        text: "Private-ish translation text",
        description: null,
      },
    );
    expect(reply.statusCode).toBe(201);
    expect(reply.headers.location).toBe(`/api/v1/messages/${MESSAGE_ID}`);
    const fields = logEventMock.mock.calls[0]?.[4] as Record<string, unknown>;
    expect(fields).toMatchObject({ actor: "admin", resource_id: MESSAGE_ID, message_key: "ui.save", language: "en" });
    expect(fields).not.toHaveProperty("text");
    expect(fields).not.toHaveProperty("description");
  });

  it("maps both duplicate prechecks and unique-index races to conflict", async () => {
    const service = new MessagesService();
    transactionQueryMock.mockResolvedValueOnce({ rows: [{ exists: 1 }], rowCount: 1 });
    await expect(service.create(admin, replyStub() as never, input())).rejects.toBeInstanceOf(ConflictProblem);

    transactionQueryMock.mockReset();
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [], rowCount: 0 })
      .mockRejectedValueOnce(Object.assign(new Error("duplicate"), { code: "23505" }));
    await expect(service.create(admin, replyStub() as never, input())).rejects.toBeInstanceOf(ConflictProblem);
    expect(recordAuditMock).not.toHaveBeenCalled();
  });

  it("updates an existing message atomically and audits the resulting snapshot", async () => {
    const updated = messageRow({ key: "ui.submit", text: "Submit", updated_at: new Date("2026-07-11T12:00:00Z") });
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [{ exists: 1 }], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [], rowCount: 0 })
      .mockResolvedValueOnce({ rows: [updated], rowCount: 1 });

    await expect(
      new MessagesService().update(admin, MESSAGE_ID, input({ key: "ui.submit", text: "Submit" })),
    ).resolves.toMatchObject({ key: "ui.submit", text: "Submit" });

    expect(recordAuditMock).toHaveBeenCalledWith(
      { query: transactionQueryMock },
      "admin",
      "message.updated",
      "message",
      MESSAGE_ID,
      expect.objectContaining({ key: "ui.submit", text: "Submit" }),
    );
    expect(logEventMock).toHaveBeenCalledWith(
      "info",
      "message_updated",
      "success",
      "Message updated",
      expect.objectContaining({ actor: "admin", message_key: "ui.submit" }),
    );
  });

  it("maps update races to conflict and missing rows to not found", async () => {
    const service = new MessagesService();
    transactionQueryMock.mockResolvedValueOnce({ rows: [], rowCount: 0 });
    await expect(service.update(admin, MESSAGE_ID, input())).rejects.toBeInstanceOf(NotFoundProblem);

    transactionQueryMock.mockReset();
    transactionQueryMock
      .mockResolvedValueOnce({ rows: [{ exists: 1 }], rowCount: 1 })
      .mockResolvedValueOnce({ rows: [], rowCount: 0 })
      .mockRejectedValueOnce(Object.assign(new Error("duplicate"), { code: "23505" }));
    await expect(service.update(admin, MESSAGE_ID, input())).rejects.toBeInstanceOf(ConflictProblem);
  });

  it("deletes and audits the returned row, while a missing row stays 404", async () => {
    const service = new MessagesService();
    transactionQueryMock.mockResolvedValueOnce({ rows: [], rowCount: 0 });
    await expect(service.delete(admin, replyStub() as never, MESSAGE_ID)).rejects.toBeInstanceOf(NotFoundProblem);

    transactionQueryMock.mockReset();
    transactionQueryMock.mockResolvedValueOnce({ rows: [messageRow()], rowCount: 1 });
    const reply = replyStub();
    await service.delete(admin, reply as never, MESSAGE_ID);

    expect(recordAuditMock).toHaveBeenCalledWith(
      { query: transactionQueryMock },
      "admin",
      "message.deleted",
      "message",
      MESSAGE_ID,
      expect.objectContaining({ key: "ui.save", text: "Save" }),
    );
    expect(reply.statusCode).toBe(204);
    expect(reply.body).toBeUndefined();
  });
});
