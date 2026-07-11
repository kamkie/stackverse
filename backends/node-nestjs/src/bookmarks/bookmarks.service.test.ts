import { beforeEach, describe, expect, it, vi } from "vitest";

const { queryMock, transactionQueryMock, withTransactionMock } = vi.hoisted(() => ({
  queryMock: vi.fn(),
  transactionQueryMock: vi.fn(),
  withTransactionMock: vi.fn(),
}));

vi.mock("../db.js", () => ({
  pool: { query: queryMock },
  withTransaction: withTransactionMock,
}));

import type { Caller } from "../auth.js";
import { decodeCursor, encodeCursor } from "../cursor.js";
import { ConflictProblem, NotFoundProblem, UnauthorizedProblem } from "../problems.js";
import type { BookmarkBodyDto } from "./bookmark.dto.js";
import { BookmarksService, type BookmarkRow } from "./bookmarks.service.js";

const FIRST_ID = "0f8fad5b-d9cb-469f-a165-70867728950e";
const SECOND_ID = "1f8fad5b-d9cb-469f-a165-70867728950e";
const THIRD_ID = "2f8fad5b-d9cb-469f-a165-70867728950e";

const bookmarkRow = (overrides: Partial<BookmarkRow> = {}): BookmarkRow => ({
  id: FIRST_ID,
  owner: "demo",
  url: "https://example.com",
  title: "Example",
  notes: null,
  tags: ["node"],
  visibility: "private",
  status: "active",
  created_at: new Date("2026-07-10T12:00:00.000Z"),
  updated_at: new Date("2026-07-10T12:00:00.000Z"),
  ...overrides,
});

const requestWith = (caller: Caller | null, query: Record<string, unknown> = {}) =>
  ({ caller, query, headers: {} }) as never;

const input = (overrides: Partial<BookmarkBodyDto> = {}): BookmarkBodyDto =>
  ({
    url: "https://updated.example.com",
    title: "Updated",
    notes: null,
    tags: ["node", "nest"],
    visibility: "private",
    ...overrides,
  }) as BookmarkBodyDto;

const replyStub = () => {
  const reply = {
    statusCode: undefined as number | undefined,
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
  withTransactionMock.mockReset();
  withTransactionMock.mockImplementation(
    async (callback: (client: { query: typeof transactionQueryMock }) => Promise<unknown>) =>
      callback({ query: transactionQueryMock }),
  );
});

describe("bookmark listings", () => {
  it("keeps the public v1 feed anonymous, filtered, and visibly deprecated", async () => {
    queryMock
      .mockResolvedValueOnce({
        rows: [bookmarkRow({ visibility: "public", owner: "another", notes: "Useful" })],
      })
      .mockResolvedValueOnce({ rows: [{ count: 3 }] });
    const reply = replyStub();

    const result = await new BookmarksService().listV1(
      requestWith(null, {
        visibility: "public",
        tag: [" Node ", "WEB"],
        q: "50%_",
        page: "1",
        size: "2",
      }),
      reply as never,
    );

    expect(reply.headers).toEqual({
      deprecation: "@1782864000",
      sunset: "Thu, 01 Jul 2027 00:00:00 GMT",
      link: '</api/v2/bookmarks>; rel="successor-version"',
    });
    expect(result).toMatchObject({ page: 1, size: 2, totalItems: 3, totalPages: 2 });
    expect(result.items).toEqual([
      expect.objectContaining({ owner: "another", visibility: "public", notes: "Useful" }),
    ]);

    const [sql, parameters] = queryMock.mock.calls[0] as [string, unknown[]];
    expect(sql).toContain("visibility = 'public' and status = 'active'");
    expect(sql).toContain("tags @>");
    expect(sql).toContain("ilike");
    expect(sql).toContain("limit 2 offset 2");
    expect(parameters).toEqual([["node", "web"], String.raw`%50\%\_%`]);
  });

  it("requires a caller outside the public feed and validates visibility", async () => {
    const service = new BookmarksService();

    await expect(service.listV1(requestWith(null), replyStub() as never)).rejects.toBeInstanceOf(UnauthorizedProblem);
    await expect(
      service.listV1(requestWith({ username: "demo", roles: [] }, { visibility: "friends" }), replyStub() as never),
    ).rejects.toMatchObject({ status: 400, title: "Bad Request" });

    expect(queryMock).not.toHaveBeenCalled();
  });

  it("uses the v2 tuple cursor and emits a cursor only when another row exists", async () => {
    const previous = { createdAt: new Date("2026-07-11T12:00:00.000Z"), id: THIRD_ID };
    const first = bookmarkRow({ id: FIRST_ID, visibility: "public", created_at: new Date("2026-07-10T12:00:00Z") });
    const second = bookmarkRow({ id: SECOND_ID, visibility: "public", created_at: new Date("2026-07-09T12:00:00Z") });
    const lookahead = bookmarkRow({ id: THIRD_ID, visibility: "public", created_at: new Date("2026-07-08T12:00:00Z") });
    queryMock.mockResolvedValueOnce({ rows: [first, second, lookahead] });

    const result = await new BookmarksService().listV2(
      requestWith(null, { visibility: "public", size: "2", cursor: encodeCursor(previous) }),
    );

    expect(result.items).toHaveLength(2);
    expect(decodeCursor(result.nextCursor as string)).toEqual({ createdAt: second.created_at, id: SECOND_ID });
    const [sql, parameters] = queryMock.mock.calls[0] as [string, unknown[]];
    expect(sql).toContain("created_at < $1 or (created_at = $1 and id < $2)");
    expect(sql).toContain("limit 3");
    expect(parameters).toEqual([previous.createdAt, previous.id]);

    queryMock.mockResolvedValueOnce({ rows: [first] });
    const finalPage = await new BookmarksService().listV2(requestWith(null, { visibility: "public", size: "2" }));
    expect(finalPage).not.toHaveProperty("nextCursor");
  });
});

describe("bookmark ownership and mutations", () => {
  it("reveals active public bookmarks but masks hidden or private non-owner rows", async () => {
    const service = new BookmarksService();
    queryMock.mockResolvedValueOnce({ rows: [bookmarkRow({ visibility: "public", owner: "another" })] });
    await expect(service.get(requestWith(null), FIRST_ID)).resolves.toMatchObject({ owner: "another" });

    queryMock.mockResolvedValueOnce({
      rows: [bookmarkRow({ visibility: "public", status: "hidden", owner: "another" })],
    });
    await expect(service.get(requestWith(null), FIRST_ID)).rejects.toBeInstanceOf(NotFoundProblem);

    queryMock.mockResolvedValueOnce({ rows: [bookmarkRow({ visibility: "private", status: "hidden" })] });
    await expect(service.get(requestWith({ username: "demo", roles: [] }), FIRST_ID)).resolves.toMatchObject({
      status: "hidden",
    });
  });

  it("creates an owner-bound bookmark and returns its resource location", async () => {
    queryMock.mockImplementationOnce(async (_sql: string, values: unknown[]) => ({
      rows: [
        bookmarkRow({
          id: values[0] as string,
          owner: values[1] as string,
          url: values[2] as string,
          title: values[3] as string,
          tags: values[5] as string[],
          visibility: values[6] as "private" | "public",
          created_at: values[7] as Date,
          updated_at: values[7] as Date,
        }),
      ],
    }));
    const reply = replyStub();

    await new BookmarksService().create(requestWith({ username: "demo", roles: [] }), reply as never, input());

    const created = reply.body as { id: string; owner: string };
    expect(reply.statusCode).toBe(201);
    expect(reply.headers.location).toBe(`/api/v1/bookmarks/${created.id}`);
    expect(created.owner).toBe("demo");
    expect(queryMock.mock.calls[0]?.[1]).toEqual([
      created.id,
      "demo",
      "https://updated.example.com",
      "Updated",
      null,
      ["node", "nest"],
      "private",
      expect.any(Date),
    ]);
  });

  it("locks updates and refuses to republish a hidden bookmark", async () => {
    transactionQueryMock.mockResolvedValueOnce({ rows: [bookmarkRow({ status: "hidden" })] });

    await expect(
      new BookmarksService().update(
        requestWith({ username: "demo", roles: [] }),
        FIRST_ID,
        input({ visibility: "public" }),
      ),
    ).rejects.toBeInstanceOf(ConflictProblem);

    expect(transactionQueryMock).toHaveBeenCalledTimes(1);
    expect(transactionQueryMock.mock.calls[0]?.[0]).toContain("for update");
  });

  it("updates only the owning row inside the transaction", async () => {
    const updated = bookmarkRow({ title: "Updated", tags: ["nest"], updated_at: new Date("2026-07-11T12:00:00Z") });
    transactionQueryMock.mockResolvedValueOnce({ rows: [bookmarkRow()] }).mockResolvedValueOnce({ rows: [updated] });

    await expect(
      new BookmarksService().update(requestWith({ username: "demo", roles: [] }), FIRST_ID, input({ tags: ["nest"] })),
    ).resolves.toMatchObject({ title: "Updated", tags: ["nest"] });

    expect(transactionQueryMock.mock.calls[1]?.[1]).toEqual([
      FIRST_ID,
      "https://updated.example.com",
      "Updated",
      null,
      ["nest"],
      "private",
      expect.any(Date),
    ]);
  });

  it("masks non-owner deletion and deletes an owned bookmark with 204", async () => {
    const service = new BookmarksService();
    queryMock.mockResolvedValueOnce({ rows: [bookmarkRow({ owner: "another" })] });
    await expect(
      service.delete(requestWith({ username: "demo", roles: [] }), replyStub() as never, FIRST_ID),
    ).rejects.toBeInstanceOf(NotFoundProblem);

    queryMock.mockResolvedValueOnce({ rows: [bookmarkRow()] }).mockResolvedValueOnce({ rows: [], rowCount: 1 });
    const reply = replyStub();
    await service.delete(requestWith({ username: "demo", roles: [] }), reply as never, FIRST_ID);
    expect(queryMock.mock.calls.at(-1)?.[0]).toContain("delete from bookmarks");
    expect(reply.statusCode).toBe(204);
    expect(reply.body).toBeUndefined();
  });

  it("returns deterministic tag counts for the caller only", async () => {
    queryMock.mockResolvedValueOnce({
      rows: [
        { tag: "node", count: 3 },
        { tag: "nest", count: 1 },
      ],
    });

    await expect(new BookmarksService().listTags(requestWith({ username: "demo", roles: [] }))).resolves.toEqual({
      tags: [
        { tag: "node", count: 3 },
        { tag: "nest", count: 1 },
      ],
    });
    expect(queryMock.mock.calls[0]?.[1]).toEqual(["demo"]);
    expect(queryMock.mock.calls[0]?.[0]).toContain("order by count desc, tag asc");
  });
});
