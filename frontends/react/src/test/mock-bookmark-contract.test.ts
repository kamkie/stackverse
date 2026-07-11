import { describe, expect, it } from "vitest";
import type { components } from "../api/schema";
import { db } from "../mocks/db";
import { MOCK_USERS, setCurrentUser } from "../mocks/state";
import { apiRequest, responseJson } from "./http";

type Bookmark = components["schemas"]["Bookmark"];
type BookmarkCursorPage = components["schemas"]["BookmarkCursorPage"];
type Problem = components["schemas"]["Problem"];
type TagCount = components["schemas"]["TagCount"];
type User = components["schemas"]["User"];

describe("bookmark mock contract", () => {
  it("keeps the anonymous surface public-only and rejects opaque cursors it cannot resolve", async () => {
    const privateList = await apiRequest("/api/v2/bookmarks");
    expect(privateList.status).toBe(401);

    const publicList = await apiRequest("/api/v2/bookmarks?visibility=public&size=100");
    expect(publicList.status).toBe(200);
    const publicPage = await responseJson<BookmarkCursorPage>(publicList);
    expect(publicPage.items.length).toBeGreaterThan(0);
    expect(
      publicPage.items.every(
        (bookmark) => bookmark.visibility === "public" && bookmark.status === "active",
      ),
    ).toBe(true);
    expect(publicPage.items.some((bookmark) => bookmark.title === "Get rich quick!!!")).toBe(
      false,
    );

    setCurrentUser(MOCK_USERS.demo);
    const filtered = await apiRequest(
      "/api/v2/bookmarks?tag=dev&tag=reference&q=reference&size=100",
    );
    const filteredPage = await responseJson<BookmarkCursorPage>(filtered);
    expect(filteredPage.items.map((bookmark) => bookmark.title)).toEqual(["MDN Web Docs"]);

    const invalidCursor = await apiRequest(
      `/api/v2/bookmarks?cursor=${encodeURIComponent(btoa("missing-bookmark"))}`,
    );
    expect(invalidCursor.status).toBe(400);
    expect(await responseJson<Problem>(invalidCursor)).toMatchObject({
      status: 400,
      title: "Invalid cursor",
    });
  });

  it("validates bookmark input and normalizes persisted tags without trusting request identity", async () => {
    const anonymousCreate = await apiRequest("/api/v1/bookmarks", {
      method: "POST",
      body: { url: "https://example.com", title: "Anonymous" },
    });
    expect(anonymousCreate.status).toBe(401);

    setCurrentUser(MOCK_USERS.demo);
    const tooLong = await apiRequest("/api/v1/bookmarks", {
      method: "POST",
      headers: { "Accept-Language": "pl" },
      body: {
        url: "",
        title: "x".repeat(201),
        notes: "n".repeat(4001),
        tags: Array.from({ length: 11 }, (_, index) => `tag-${index}`),
      },
    });
    expect(tooLong.status).toBe(400);
    const tooLongProblem = await responseJson<Problem>(tooLong);
    expect(tooLongProblem.errors?.map((error) => error.field)).toEqual([
      "url",
      "title",
      "notes",
      "tags",
    ]);
    expect(tooLongProblem.errors?.every((error) => error.message.length > 0)).toBe(true);

    const invalidValues = await apiRequest("/api/v1/bookmarks", {
      method: "POST",
      body: {
        url: "javascript:alert(1)",
        title: "Invalid",
        tags: ["not valid"],
      },
    });
    expect(invalidValues.status).toBe(400);
    expect(
      (await responseJson<Problem>(invalidValues)).errors?.map((error) => error.messageKey),
    ).toEqual(["validation.url.invalid", "validation.tag.invalid"]);

    const createdResponse = await apiRequest("/api/v1/bookmarks", {
      method: "POST",
      body: {
        url: "https://example.com/new",
        title: "Normalized",
        tags: [" Reading ", "reading", "DEV"],
      },
    });
    expect(createdResponse.status).toBe(201);
    const created = await responseJson<Bookmark>(createdResponse);
    expect(created).toMatchObject({
      owner: "demo",
      tags: ["reading", "dev"],
      visibility: "private",
      status: "active",
    });
    expect(createdResponse.headers.get("Location")).toBe(`/api/v1/bookmarks/${created.id}`);
  });

  it("masks ownership failures and preserves moderation state across owner updates", async () => {
    const privateBookmark = db.bookmarks.find(
      (bookmark) => bookmark.owner === "demo" && bookmark.visibility === "private",
    );
    const hiddenBookmark = db.bookmarks.find(
      (bookmark) => bookmark.owner === "demo" && bookmark.status === "hidden",
    );
    const carolBookmark = db.bookmarks.find((bookmark) => bookmark.owner === "carol");
    if (!privateBookmark || !hiddenBookmark || !carolBookmark) {
      throw new Error("bookmark seed data is incomplete");
    }

    expect((await apiRequest(`/api/v1/bookmarks/${privateBookmark.id}`)).status).toBe(404);
    expect((await apiRequest(`/api/v1/bookmarks/${carolBookmark.id}`)).status).toBe(200);
    expect(
      (
        await apiRequest(`/api/v1/bookmarks/${hiddenBookmark.id}`, {
          method: "PUT",
          body: {
            url: hiddenBookmark.url,
            title: hiddenBookmark.title,
            visibility: "private",
          },
        })
      ).status,
    ).toBe(401);

    setCurrentUser(MOCK_USERS.demo);
    expect(
      (
        await apiRequest(`/api/v1/bookmarks/${carolBookmark.id}`, {
          method: "PUT",
          body: {
            url: carolBookmark.url,
            title: carolBookmark.title,
            visibility: "public",
          },
        })
      ).status,
    ).toBe(404);

    const republish = await apiRequest(`/api/v1/bookmarks/${hiddenBookmark.id}`, {
      method: "PUT",
      body: {
        url: hiddenBookmark.url,
        title: hiddenBookmark.title,
        visibility: "public",
      },
    });
    expect(republish.status).toBe(409);
    expect((await responseJson<Problem>(republish)).detail).toBeTruthy();

    const updatedResponse = await apiRequest(`/api/v1/bookmarks/${hiddenBookmark.id}`, {
      method: "PUT",
      body: {
        url: "https://example.com/still-hidden",
        title: "Still hidden",
        tags: [" MODERATION ", "moderation"],
        visibility: "private",
      },
    });
    expect(updatedResponse.status).toBe(200);
    const updated = await responseJson<Bookmark>(updatedResponse);
    expect(updated).toMatchObject({
      status: "hidden",
      visibility: "private",
      tags: ["moderation"],
    });
    expect(updated.notes).toBeUndefined();

    setCurrentUser(MOCK_USERS.moderator);
    expect(
      (
        await apiRequest(`/api/v1/bookmarks/${updated.id}`, {
          method: "DELETE",
        })
      ).status,
    ).toBe(404);
    setCurrentUser(MOCK_USERS.demo);
    expect(
      (
        await apiRequest(`/api/v1/bookmarks/${updated.id}`, {
          method: "DELETE",
        })
      ).status,
    ).toBe(204);
    expect(db.bookmarks.some((bookmark) => bookmark.id === updated.id)).toBe(false);
  });

  it("derives identity and tag counts from the current session and persisted bookmarks", async () => {
    expect((await apiRequest("/api/v1/tags")).status).toBe(401);
    expect((await apiRequest("/api/v1/me")).status).toBe(401);

    setCurrentUser(MOCK_USERS.demo);
    const tagsResponse = await apiRequest("/api/v1/tags");
    const tagsBody = await responseJson<{ tags: TagCount[] }>(tagsResponse);
    expect(tagsBody.tags[0]).toMatchObject({ tag: "reading" });
    expect(tagsBody.tags[0]?.count).toBeGreaterThan(tagsBody.tags.at(-1)?.count ?? 0);

    const me = await responseJson<User>(await apiRequest("/api/v1/me"));
    expect(me).toEqual({ username: "demo", roles: [] });
  });
});
