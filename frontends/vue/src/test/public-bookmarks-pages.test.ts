import { enableAutoUnmount, mount } from "@vue/test-utils";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import type { Bookmark, Report } from "../types";
import { problem, seedMessages, settle, stubFetch } from "./page-test-helpers";

enableAutoUnmount(afterEach);

const timestamp = "2026-07-11T12:00:00.000Z";

function bookmark(overrides: Partial<Bookmark> = {}): Bookmark {
  return {
    id: "00000000-0000-4000-8000-000000000101",
    owner: "alice",
    url: "https://example.com/vue",
    title: "Vue guide",
    notes: "A useful reference",
    tags: ["vue"],
    visibility: "public",
    status: "active",
    createdAt: timestamp,
    updatedAt: timestamp,
    ...overrides,
  };
}

function report(bookmarkId: string): Report {
  return {
    id: "00000000-0000-4000-8000-000000000201",
    bookmarkId,
    reporter: "demo",
    reason: "offensive",
    comment: "Needs review",
    status: "open",
    createdAt: timestamp,
  };
}

async function preparePageMessages(): Promise<void> {
  await seedMessages({
    "ui.action.add": "Add bookmark",
    "ui.action.delete": "Delete",
    "ui.action.edit": "Edit",
    "ui.action.load-more": "Load more",
    "ui.action.report": "Report",
    "ui.action.save": "Save",
    "ui.report.reported": "Reported",
    "ui.toast.bookmark-deleted": "Bookmark deleted",
    "ui.toast.report-duplicate": "Already reported",
    "ui.toast.report-submitted": "Report submitted",
  });
}

describe("public feed", () => {
  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
    vi.useRealTimers();
    document.body.innerHTML = "";
  });

  it("loads cursor pages and submits a report for an authenticated user", async () => {
    const first = bookmark();
    const second = bookmark({
      id: "00000000-0000-4000-8000-000000000102",
      title: "Vue testing",
    });
    const requests = stubFetch(async (request) => {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/api/v2/bookmarks") {
        return Response.json(
          url.searchParams.get("cursor")
            ? { items: [second] }
            : { items: [first], nextCursor: "next-page" },
        );
      }
      if (request.method === "POST" && url.pathname === `/api/v1/bookmarks/${first.id}/reports`) {
        return Response.json(report(first.id), { status: 201 });
      }
      return new Response(null, { status: 404 });
    });
    await preparePageMessages();
    const auth = await import("../auth");
    auth.session.value = { authenticated: true, username: "demo" };
    const { default: PublicFeedPage } = await import("../pages/PublicFeedPage.vue");
    const wrapper = mount(PublicFeedPage);

    await settle();
    expect(wrapper.text()).toContain("Vue guide");

    await wrapper
      .findAll("button")
      .find((button) => button.text() === "Load more")
      ?.trigger("click");
    await settle();

    expect(wrapper.text()).toContain("Vue testing");
    expect(
      requests.some((request) => new URL(request.url).searchParams.get("cursor") === "next-page"),
    ).toBe(true);

    await wrapper
      .findAll("button")
      .find((button) => button.text() === "Report")
      ?.trigger("click");
    const dialog = wrapper.get("dialog");
    await dialog.get("select").setValue("offensive");
    await dialog.get("textarea").setValue("Needs review");
    await dialog.get("form").trigger("submit");
    await settle();

    const submitted = requests.find(
      (request) => request.method === "POST" && new URL(request.url).pathname.includes("reports"),
    );
    expect(await submitted?.json()).toEqual({ reason: "offensive", comment: "Needs review" });
    expect(wrapper.find("dialog").exists()).toBe(false);
    const [{ isReported }, { toasts }] = await Promise.all([
      import("../reportedStore"),
      import("../toast"),
    ]);
    expect(isReported(first.id)).toBe(true);
    expect(toasts.value.at(-1)?.message).toBe("Report submitted");
  });

  it("marks duplicate reports locally and surfaces feed failures", async () => {
    const item = bookmark();
    let failFeed = false;
    stubFetch((request) => {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/api/v2/bookmarks") {
        return failFeed ? problem(503, "Feed unavailable") : Response.json({ items: [item] });
      }
      if (request.method === "POST") return problem(409, "Already open");
      return new Response(null, { status: 404 });
    });
    await preparePageMessages();
    const auth = await import("../auth");
    auth.session.value = { authenticated: true, username: "demo" };
    const { default: PublicFeedPage } = await import("../pages/PublicFeedPage.vue");
    const wrapper = mount(PublicFeedPage);
    await settle();

    await wrapper.get("button").trigger("click");
    await wrapper.get("dialog form").trigger("submit");
    await settle();

    expect(wrapper.find("dialog").exists()).toBe(false);
    expect(wrapper.get("button").text()).toBe("Reported");
    expect(wrapper.get("button").attributes()).toHaveProperty("disabled");
    expect((await import("../toast")).toasts.value.at(-1)?.message).toBe("Already reported");

    failFeed = true;
    const search = wrapper.get<HTMLInputElement>("input[type='search']");
    vi.useFakeTimers();
    await search.setValue("failing query");
    vi.advanceTimersByTime(250);
    await settle();

    expect(wrapper.get("[role='alert']").text()).toBe("Feed unavailable");
  });
});

describe("my bookmarks", () => {
  beforeEach(() => {
    vi.resetModules();
  });

  afterEach(() => {
    document.body.innerHTML = "";
  });

  it("creates, edits, and deletes bookmarks while refreshing tags", async () => {
    const original = bookmark({ owner: "demo", visibility: "private" });
    const created = bookmark({
      id: "00000000-0000-4000-8000-000000000103",
      owner: "demo",
      title: "Created bookmark",
      notes: "From the form",
      tags: ["vue", "testing"],
      visibility: "public",
    });
    const requests = stubFetch(async (request) => {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/api/v2/bookmarks") {
        return Response.json({ items: [original] });
      }
      if (request.method === "GET" && url.pathname === "/api/v1/tags") {
        return Response.json({ tags: [{ tag: "vue", count: 1 }] });
      }
      if (request.method === "POST" && url.pathname === "/api/v1/bookmarks") {
        return Response.json(created, { status: 201 });
      }
      if (request.method === "PUT" && url.pathname === `/api/v1/bookmarks/${created.id}`) {
        return Response.json({ ...created, title: "Edited bookmark" });
      }
      if (request.method === "DELETE" && url.pathname === `/api/v1/bookmarks/${created.id}`) {
        return new Response(null, { status: 204 });
      }
      return new Response(null, { status: 404 });
    });
    await preparePageMessages();
    const { default: MyBookmarksPage } = await import("../pages/MyBookmarksPage.vue");
    const wrapper = mount(MyBookmarksPage);
    await settle();

    expect(wrapper.text()).toContain("Vue guide");
    expect(wrapper.text()).toContain("vue");

    await wrapper
      .findAll("button")
      .find((button) => button.text() === "Add bookmark")
      ?.trigger("click");
    let dialog = wrapper.get("dialog");
    const inputs = dialog.findAll<HTMLInputElement>("input");
    await inputs[0]?.setValue("https://example.com/created");
    await inputs[1]?.setValue("Created bookmark");
    await dialog.get("textarea").setValue("From the form");
    await inputs[2]?.setValue("vue testing");
    await dialog.get("select").setValue("public");
    await dialog.get("form").trigger("submit");
    await settle();

    expect(wrapper.text()).toContain("Created bookmark");
    const post = requests.find((request) => request.method === "POST");
    expect(await post?.json()).toEqual({
      url: "https://example.com/created",
      title: "Created bookmark",
      notes: "From the form",
      tags: ["vue", "testing"],
      visibility: "public",
    });

    await wrapper
      .findAll("button")
      .find((button) => button.text() === "Edit")
      ?.trigger("click");
    dialog = wrapper.get("dialog");
    await dialog.findAll<HTMLInputElement>("input")[1]?.setValue("Edited bookmark");
    await dialog.get("form").trigger("submit");
    await settle();
    expect(wrapper.text()).toContain("Edited bookmark");
    const update = requests.find((request) => request.method === "PUT");
    expect(new URL(update?.url ?? location.origin).pathname).toBe(
      `/api/v1/bookmarks/${created.id}`,
    );
    expect(await update?.json()).toEqual({
      url: created.url,
      title: "Edited bookmark",
      notes: created.notes,
      tags: created.tags,
      visibility: created.visibility,
    });

    await wrapper
      .findAll("button")
      .find((button) => button.text() === "Delete")
      ?.trigger("click");
    await wrapper.get("dialog form").trigger("submit");
    await settle();

    expect(wrapper.text()).not.toContain("Edited bookmark");
    const removal = requests.find((request) => request.method === "DELETE");
    expect(new URL(removal?.url ?? location.origin).pathname).toBe(
      `/api/v1/bookmarks/${created.id}`,
    );
    expect((await import("../toast")).toasts.value.at(-1)?.message).toBe("Bookmark deleted");
  });

  it("keeps the form open and maps localized validation errors to fields", async () => {
    stubFetch((request) => {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/api/v2/bookmarks") {
        return Response.json({ items: [] });
      }
      if (request.method === "GET" && url.pathname === "/api/v1/tags") {
        return Response.json({ tags: [] });
      }
      if (request.method === "POST") {
        return problem(400, "Invalid bookmark", [
          {
            field: "title",
            messageKey: "validation.required",
            message: "Localized title error",
          },
        ]);
      }
      return new Response(null, { status: 404 });
    });
    await preparePageMessages();
    const { default: MyBookmarksPage } = await import("../pages/MyBookmarksPage.vue");
    const wrapper = mount(MyBookmarksPage);
    await settle();

    await wrapper
      .findAll("button")
      .find((button) => button.text() === "Add bookmark")
      ?.trigger("click");
    const dialog = wrapper.get("dialog");
    await dialog.findAll<HTMLInputElement>("input")[0]?.setValue("https://example.com");
    await dialog.get("form").trigger("submit");
    await settle();

    expect(wrapper.find("dialog").exists()).toBe(true);
    expect(wrapper.get(".sv-field-error").text()).toBe("Localized title error");
    expect(wrapper.get(".sv-field.is-invalid input").attributes("aria-invalid")).toBe("true");
  });
});
