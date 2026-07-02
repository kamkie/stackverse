// SPEC rule 4: the caller's tags with usage counts, most used first.
import { createBookmark, expect, expectProblem, test, uid } from "./fixtures";

interface TagList {
  tags: { tag: string; count: number }[];
}

test("tags require authentication", async ({ anon }) => {
  await expectProblem(await anon.get("/api/v1/tags"), 401);
});

test("tags aggregate the caller's usage, most used first", async ({ mentor }) => {
  const heavy = `heavy-${uid()}`;
  const light = `light-${uid()}`;
  await createBookmark(mentor, { url: "https://example.com/1", title: "one", tags: [heavy, light] });
  await createBookmark(mentor, { url: "https://example.com/2", title: "two", tags: [heavy] });

  const response = await mentor.get("/api/v1/tags");
  expect(response.status(), await response.text()).toBe(200);
  const list = (await response.json()) as TagList;

  const heavyIndex = list.tags.findIndex((entry) => entry.tag === heavy);
  const lightIndex = list.tags.findIndex((entry) => entry.tag === light);
  expect(heavyIndex).toBeGreaterThanOrEqual(0);
  expect(lightIndex).toBeGreaterThanOrEqual(0);
  expect(list.tags[heavyIndex]?.count).toBe(2);
  expect(list.tags[lightIndex]?.count).toBe(1);
  expect(heavyIndex).toBeLessThan(lightIndex);

  const counts = list.tags.map((entry) => entry.count);
  expect([...counts].sort((a, b) => b - a)).toEqual(counts);
});

test("tags are scoped to the caller", async ({ demo, mentor }) => {
  const foreign = `foreign-${uid()}`;
  await createBookmark(demo, { url: "https://example.com/3", title: "not mentors", tags: [foreign] });
  const list = (await (await mentor.get("/api/v1/tags")).json()) as TagList;
  expect(list.tags.find((entry) => entry.tag === foreign)).toBeUndefined();
});
