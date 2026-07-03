// Bookmark CRUD, ownership masking (SPEC rule 1), the anonymous public surface
// (rule 2), listing filters (rule 3), validation (rules 5 and 11).
import {
  createBookmark,
  expect,
  expectProblem,
  seedMessages,
  test,
  uid,
  type Bookmark,
} from "./fixtures";

test("create applies server-managed defaults and a Location header", async ({ demo }) => {
  const response = await demo.post("/api/v1/bookmarks", {
    data: { url: "https://example.com/minimal", title: `minimal ${uid()}` },
  });
  expect(response.status(), await response.text()).toBe(201);
  const created = (await response.json()) as Bookmark;
  expect(response.headers()["location"]).toContain(`/api/v1/bookmarks/${created.id}`);
  expect(created.id).toMatch(/^[0-9a-f-]{36}$/);
  expect(created.owner).toBe("demo");
  expect(created.visibility).toBe("private");
  expect(created.status).toBe("active");
  expect(created.tags).toEqual([]);
  expect(Date.parse(created.createdAt)).not.toBeNaN();
  expect(Date.parse(created.updatedAt)).not.toBeNaN();
});

test("unknown JSON fields are ignored, not rejected", async ({ demo }) => {
  const response = await demo.post("/api/v1/bookmarks", {
    data: { url: "https://example.com/extra", title: `extra ${uid()}`, unexpected: "field" },
  });
  expect(response.status(), await response.text()).toBe(201);
});

test("tags are stored deduplicated", async ({ demo }) => {
  const tag = `dup-${uid()}`;
  const created = await createBookmark(demo, {
    url: "https://example.com/tags",
    title: `tags ${uid()}`,
    tags: [tag, tag],
  });
  expect(created.tags).toEqual([tag]);
});

test("a private bookmark is invisible to other users and anonymous callers", async ({ demo, mentor, anon }) => {
  const created = await createBookmark(demo, {
    url: "https://example.com/private",
    title: `private ${uid()}`,
    visibility: "private",
  });
  expect((await demo.get(`/api/v1/bookmarks/${created.id}`)).status()).toBe(200);
  await expectProblem(await mentor.get(`/api/v1/bookmarks/${created.id}`), 404);
  await expectProblem(await anon.get(`/api/v1/bookmarks/${created.id}`), 404);
});

test("a public bookmark is readable by anyone", async ({ demo, mentor, anon }) => {
  const created = await createBookmark(demo, {
    url: "https://example.com/public",
    title: `public ${uid()}`,
    visibility: "public",
  });
  expect((await mentor.get(`/api/v1/bookmarks/${created.id}`)).status()).toBe(200);
  expect((await anon.get(`/api/v1/bookmarks/${created.id}`)).status()).toBe(200);
});

test("update and delete are owner-only; non-owners get 404 even for public bookmarks", async ({ demo, mentor }) => {
  const created = await createBookmark(demo, {
    url: "https://example.com/owned",
    title: `owned ${uid()}`,
    visibility: "public",
  });
  const update = { url: created.url, title: "hijacked" };
  await expectProblem(await mentor.put(`/api/v1/bookmarks/${created.id}`, { data: update }), 404);
  await expectProblem(await mentor.delete(`/api/v1/bookmarks/${created.id}`), 404);
  // the 404 must be a refusal, not a cover-up — the bookmark is untouched
  const after = await demo.get(`/api/v1/bookmarks/${created.id}`);
  expect(after.status()).toBe(200);
  expect(((await after.json()) as Bookmark).title).toBe(created.title);
});

test("the owner can update; owner and createdAt stay immutable", async ({ demo }) => {
  const created = await createBookmark(demo, {
    url: "https://example.com/before",
    title: `before ${uid()}`,
  });
  const response = await demo.put(`/api/v1/bookmarks/${created.id}`, {
    data: { url: "https://example.com/after", title: "after", visibility: "public" },
  });
  expect(response.status(), await response.text()).toBe(200);
  const updated = (await response.json()) as Bookmark;
  expect(updated.url).toBe("https://example.com/after");
  expect(updated.visibility).toBe("public");
  expect(updated.owner).toBe("demo");
  expect(updated.createdAt).toBe(created.createdAt);
});

test("delete answers 204 and the bookmark is gone", async ({ demo }) => {
  const created = await createBookmark(demo, {
    url: "https://example.com/doomed",
    title: `doomed ${uid()}`,
  });
  expect((await demo.delete(`/api/v1/bookmarks/${created.id}`)).status()).toBe(204);
  await expectProblem(await demo.get(`/api/v1/bookmarks/${created.id}`), 404);
});

test("the listing requires authentication unless visibility=public is requested", async ({ anon }) => {
  await expectProblem(await anon.get("/api/v1/bookmarks"), 401);
  await expectProblem(await anon.get("/api/v2/bookmarks"), 401);
  expect((await anon.get("/api/v1/bookmarks?visibility=public")).status()).toBe(200);
  expect((await anon.get("/api/v2/bookmarks?visibility=public")).status()).toBe(200);
});

test("the public feed contains only public bookmarks, from any owner", async ({ demo, mentor, anon }) => {
  const tag = `feed-${uid()}`;
  await createBookmark(demo, { url: "https://example.com/f1", title: "feed pub", tags: [tag], visibility: "public" });
  await createBookmark(mentor, { url: "https://example.com/f2", title: "feed pub 2", tags: [tag], visibility: "public" });
  await createBookmark(demo, { url: "https://example.com/f3", title: "feed priv", tags: [tag], visibility: "private" });
  const response = await anon.get(`/api/v1/bookmarks?visibility=public&tag=${tag}&size=100`);
  const page = (await response.json()) as { items: Bookmark[] };
  expect(page.items).toHaveLength(2);
  expect(new Set(page.items.map((item) => item.owner))).toEqual(new Set(["demo", "mentor"]));
  for (const item of page.items) expect(item.visibility).toBe("public");
});

test("q matches case-insensitively over title and notes", async ({ demo }) => {
  const needle = `needle${uid()}`;
  await createBookmark(demo, { url: "https://example.com/q1", title: `Title ${needle.toUpperCase()}` });
  await createBookmark(demo, { url: "https://example.com/q2", title: "unrelated", notes: `notes ${needle}` });
  const response = await demo.get(`/api/v1/bookmarks?q=${needle}`);
  const page = (await response.json()) as { items: Bookmark[]; totalItems: number };
  expect(page.totalItems).toBe(2);
});

test("repeated tag filters combine with AND", async ({ demo }) => {
  const a = `and-a-${uid()}`;
  const b = `and-b-${uid()}`;
  const both = await createBookmark(demo, { url: "https://example.com/t1", title: "both", tags: [a, b] });
  await createBookmark(demo, { url: "https://example.com/t2", title: "only a", tags: [a] });
  const response = await demo.get(`/api/v1/bookmarks?tag=${a}&tag=${b}`);
  const page = (await response.json()) as { items: Bookmark[] };
  expect(page.items.map((item) => item.id)).toEqual([both.id]);
});

test("bookmark listings reject invalid repeated tag query values", async ({ demo }) => {
  for (const path of ["/api/v1/bookmarks", "/api/v2/bookmarks"]) {
    const problem = await expectProblem(
      await demo.get(`${path}?tag=valid-tag&tag=${encodeURIComponent("no spaces!")}`),
      400,
    );
    expect(problem.errors).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ field: "tag", messageKey: "validation.tag.invalid" }),
      ]),
    );
  }
});

test("invalid input yields a 400 problem with field errors from the validation namespace", async ({ demo }) => {
  const problem = await expectProblem(await demo.post("/api/v1/bookmarks", { data: {} }), 400);
  const fields = (problem.errors ?? []).map((error) => error.field);
  expect(fields).toEqual(expect.arrayContaining(["url", "title"]));
  for (const error of problem.errors ?? []) {
    expect(error.messageKey).toMatch(/^validation\./);
    expect(error.message.length).toBeGreaterThan(0);
  }
});

test.describe("field validation", () => {
  const cases: { name: string; body: Record<string, unknown>; field: string }[] = [
    { name: "relative url", body: { url: "/not/absolute", title: "t" }, field: "url" },
    { name: "non-http scheme", body: { url: "ftp://example.com", title: "t" }, field: "url" },
    { name: "overlong title", body: { url: "https://example.com", title: "x".repeat(201) }, field: "title" },
    { name: "overlong notes", body: { url: "https://example.com", title: "t", notes: "x".repeat(4001) }, field: "notes" },
    {
      name: "too many tags",
      body: { url: "https://example.com", title: "t", tags: Array.from({ length: 11 }, (_, i) => `tag-${i}`) },
      field: "tags",
    },
    { name: "invalid tag characters", body: { url: "https://example.com", title: "t", tags: ["no spaces!"] }, field: "tags" },
  ];
  for (const { name, body, field } of cases) {
    test(`rejects ${name}`, async ({ demo }) => {
      const problem = await expectProblem(await demo.post("/api/v1/bookmarks", { data: body }), 400);
      expect((problem.errors ?? []).map((error) => error.field).join(",")).toContain(field);
    });
  }
});

test("validation messages are localized from the seeded messages (Accept-Language)", async ({ demo }) => {
  const en = seedMessages("en");
  const pl = seedMessages("pl");
  const problem = await expectProblem(
    await demo.post("/api/v1/bookmarks", { data: {}, headers: { "Accept-Language": "pl" } }),
    400,
  );
  expect(problem.errors?.length).toBeGreaterThan(0);
  for (const error of problem.errors ?? []) {
    // rule 11: localized per rule 8, falling back to en — both texts come from the seed
    expect(error.message).toBe(pl[error.messageKey] ?? en[error.messageKey]);
  }
});
