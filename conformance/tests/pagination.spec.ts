// The v1 → v2 pagination exhibit (SPEC "API versioning"): offset semantics and
// RFC 9745/8594/8288 deprecation signaling on v1, opaque stable cursors on v2.
import { createBookmark, expect, expectProblem, test, uid, type Bookmark } from "./fixtures";

interface OffsetPage {
  items: Bookmark[];
  page: number;
  size: number;
  totalItems: number;
  totalPages: number;
}

interface CursorPage {
  items: Bookmark[];
  nextCursor?: string;
}

/** Five bookmarks under a run-unique tag, oldest → newest. */
async function seedFive(api: Parameters<typeof createBookmark>[0]): Promise<{ tag: string; ids: string[] }> {
  const tag = `page-${uid()}`;
  const ids: string[] = [];
  for (let i = 0; i < 5; i++) {
    const created = await createBookmark(api, {
      url: `https://example.com/page/${i}`,
      title: `pagination ${i} ${tag}`,
      tags: [tag],
    });
    ids.push(created.id);
  }
  return { tag, ids };
}

test("v1 paginates by offset with page metadata, newest first", async ({ demo }) => {
  const { tag, ids } = await seedFive(demo);
  const first = (await (await demo.get(`/api/v1/bookmarks?tag=${tag}&size=2`)).json()) as OffsetPage;
  expect(first.totalItems).toBe(5);
  expect(first.totalPages).toBe(3);
  expect(first.page).toBe(0);
  expect(first.items).toHaveLength(2);

  const last = (await (await demo.get(`/api/v1/bookmarks?tag=${tag}&size=2&page=2`)).json()) as OffsetPage;
  expect(last.items).toHaveLength(1);

  const seen = [...first.items.map((item) => item.id), ...last.items.map((item) => item.id)];
  expect(new Set(seen).size).toBe(3);
  // newest first: the first page starts with the most recent seed
  expect(first.items[0]?.id).toBe(ids[ids.length - 1]);
});

test("every v1 listing response carries the three deprecation headers", async ({ demo }) => {
  const response = await demo.get("/api/v1/bookmarks?size=1");
  expect(response.status()).toBe(200);
  const headers = response.headers();
  expect(headers["deprecation"], "RFC 9745 Deprecation").toMatch(/^@\d+$/);
  expect(Date.parse(headers["sunset"] ?? ""), "RFC 8594 Sunset must be an HTTP-date").not.toBeNaN();
  expect(headers["link"]).toContain("</api/v2/bookmarks>");
  expect(headers["link"]).toContain('rel="successor-version"');
});

test("v2 is not deprecated and returns cursor pages", async ({ demo }) => {
  const response = await demo.get("/api/v2/bookmarks?size=1");
  expect(response.status()).toBe(200);
  expect(response.headers()["deprecation"]).toBeUndefined();
  expect(response.headers()["sunset"]).toBeUndefined();
});

test("v2 walks all items through opaque cursors without skips or duplicates", async ({ demo }) => {
  const { tag, ids } = await seedFive(demo);
  const collected: string[] = [];
  let cursor: string | undefined;
  let hops = 0;
  do {
    const query = cursor === undefined ? "" : `&cursor=${encodeURIComponent(cursor)}`;
    const page = (await (await demo.get(`/api/v2/bookmarks?tag=${tag}&size=2${query}`)).json()) as CursorPage;
    collected.push(...page.items.map((item) => item.id));
    cursor = page.nextCursor;
    expect(++hops).toBeLessThan(10);
  } while (cursor !== undefined);
  expect(collected).toHaveLength(5);
  expect(new Set(collected)).toEqual(new Set(ids));
  // newest first across the whole walk
  expect(collected).toEqual([...ids].reverse());
});

test("v2 cursors are stable under concurrent inserts", async ({ demo }) => {
  const { tag, ids } = await seedFive(demo);
  const first = (await (await demo.get(`/api/v2/bookmarks?tag=${tag}&size=2`)).json()) as CursorPage;
  expect(first.nextCursor).toBeDefined();

  // a newer item arrives between page fetches — it must not shift the walk
  await createBookmark(demo, { url: "https://example.com/late", title: `late ${tag}`, tags: [tag] });

  const rest: string[] = [];
  let cursor = first.nextCursor;
  while (cursor !== undefined) {
    const page = (await (
      await demo.get(`/api/v2/bookmarks?tag=${tag}&size=2&cursor=${encodeURIComponent(cursor)}`)
    ).json()) as CursorPage;
    rest.push(...page.items.map((item) => item.id));
    cursor = page.nextCursor;
  }
  const walk = [...first.items.map((item) => item.id), ...rest];
  // no duplicates, no skips: exactly the original five, the late insert excluded
  expect(walk).toEqual([...ids].reverse());
});

test("a malformed cursor is a 400 problem, not a 500", async ({ demo }) => {
  await expectProblem(await demo.get("/api/v2/bookmarks?cursor=definitely-not-a-cursor"), 400);
});

test("size and page bounds are validated on both versions", async ({ demo }) => {
  await expectProblem(await demo.get("/api/v1/bookmarks?size=0"), 400);
  await expectProblem(await demo.get("/api/v1/bookmarks?size=101"), 400);
  await expectProblem(await demo.get("/api/v1/bookmarks?page=-1"), 400);
  await expectProblem(await demo.get("/api/v2/bookmarks?size=0"), 400);
  await expectProblem(await demo.get("/api/v2/bookmarks?size=101"), 400);
});
