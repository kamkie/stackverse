import {
  cleanup,
  fireEvent,
  render,
  screen,
  waitFor,
} from "@testing-library/svelte";
import { afterEach, beforeEach, describe, expect, it, vi } from "vitest";
import MyBookmarksPage from "../pages/MyBookmarksPage.svelte";
import PublicFeedPage from "../pages/PublicFeedPage.svelte";
import { removeReported } from "../lib/reportedStore";
import {
  bookmark,
  installDialogPolyfill,
  problem,
  seedMessages,
  setIdentity,
  stubFetch,
} from "./test-helpers";

beforeEach(() => {
  seedMessages();
  installDialogPolyfill();
  setIdentity(null);
});

afterEach(() => {
  cleanup();
  vi.unstubAllGlobals();
});

describe("my bookmarks", () => {
  it("creates, edits, and deletes bookmarks while refreshing tag state", async () => {
    const original = bookmark({ owner: "demo", visibility: "private" });
    const created = bookmark({
      id: "00000000-0000-4000-8000-000000000103",
      owner: "demo",
      title: "Created bookmark",
      notes: "From the form",
      tags: ["svelte", "testing"],
      visibility: "public",
    });
    let items = [original];
    let postBody: unknown;
    let putBody: unknown;
    const requests = stubFetch(async (request) => {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/api/v2/bookmarks") {
        return Response.json({ items });
      }
      if (request.method === "GET" && url.pathname === "/api/v1/tags") {
        return Response.json({
          tags: [{ tag: "svelte", count: items.length }],
        });
      }
      if (request.method === "POST" && url.pathname === "/api/v1/bookmarks") {
        postBody = await request.clone().json();
        items = [created];
        return Response.json(created, { status: 201 });
      }
      if (
        request.method === "PUT" &&
        url.pathname === `/api/v1/bookmarks/${created.id}`
      ) {
        putBody = await request.clone().json();
        items = [{ ...created, title: "Edited bookmark" }];
        return Response.json(items[0]);
      }
      if (
        request.method === "DELETE" &&
        url.pathname === `/api/v1/bookmarks/${created.id}`
      ) {
        items = [];
        return new Response(null, { status: 204 });
      }
      return new Response(null, { status: 404 });
    });
    const toast = vi.fn();

    render(MyBookmarksPage, { toast });
    expect(await screen.findByText("Svelte guide")).toBeTruthy();
    expect(
      await screen.findByRole("button", { name: "svelte 1" }),
    ).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Add bookmark" }));
    await fireEvent.input(screen.getByLabelText("URL"), {
      target: { value: "https://example.com/created" },
    });
    await fireEvent.input(screen.getByLabelText("Title"), {
      target: { value: "Created bookmark" },
    });
    await fireEvent.input(screen.getByLabelText("Notes"), {
      target: { value: "From the form" },
    });
    await fireEvent.input(screen.getByLabelText(/Tags/), {
      target: { value: "svelte, testing" },
    });
    await fireEvent.change(screen.getByLabelText("Visibility"), {
      target: { value: "public" },
    });
    await fireEvent.submit(
      screen.getByRole("dialog").querySelector("form") as HTMLFormElement,
    );

    expect(await screen.findByText("Created bookmark")).toBeTruthy();
    expect(postBody).toEqual({
      url: "https://example.com/created",
      title: "Created bookmark",
      notes: "From the form",
      tags: ["svelte", "testing"],
      visibility: "public",
    });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    await fireEvent.click(screen.getByRole("button", { name: "Edit" }));
    const editDialog = await screen.findByRole("dialog");
    await fireEvent.input(withinDialogLabel(editDialog, "Title"), {
      target: { value: "Edited bookmark" },
    });
    await fireEvent.submit(editDialog.querySelector("form") as HTMLFormElement);
    expect(await screen.findByText("Edited bookmark")).toBeTruthy();
    expect(putBody).toEqual({
      url: created.url,
      title: "Edited bookmark",
      notes: created.notes,
      tags: created.tags,
      visibility: created.visibility,
    });
    await waitFor(() => expect(screen.queryByRole("dialog")).toBeNull());

    await fireEvent.click(screen.getByRole("button", { name: "Delete" }));
    const deleteDialog = await screen.findByRole("dialog");
    await fireEvent.submit(
      deleteDialog.querySelector("form") as HTMLFormElement,
    );
    expect(await screen.findByText("No bookmarks yet")).toBeTruthy();
    expect(toast).toHaveBeenCalledWith("Bookmark deleted");
    expect(
      requests.filter(
        (request) =>
          request.method === "GET" &&
          new URL(request.url).pathname === "/api/v1/tags",
      ).length,
    ).toBeGreaterThan(1);
  });

  it("keeps the create form open for localized contract validation", async () => {
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
            messageKey: "validation.title.required",
            message: "Localized title error",
          },
        ]);
      }
      return new Response(null, { status: 404 });
    });
    render(MyBookmarksPage, { toast: vi.fn() });
    await screen.findByText("No bookmarks yet");
    await fireEvent.click(screen.getByRole("button", { name: "Add bookmark" }));
    await fireEvent.input(screen.getByLabelText("URL"), {
      target: { value: "https://example.com" },
    });
    await fireEvent.submit(
      screen.getByRole("dialog").querySelector("form") as HTMLFormElement,
    );
    expect(await screen.findByText("Localized title error")).toBeTruthy();
    expect(screen.getByRole("dialog")).toBeTruthy();
  });
});

describe("public feed", () => {
  it("loads cursor pages and handles duplicate reports as durable UI state", async () => {
    const first = bookmark();
    const second = bookmark({
      id: "00000000-0000-4000-8000-000000000102",
      title: "Svelte testing",
    });
    removeReported(first.id);
    setIdentity({ username: "demo", roles: [] });
    let submittedBody: unknown;
    const requests = stubFetch(async (request) => {
      const url = new URL(request.url);
      if (request.method === "GET" && url.pathname === "/api/v2/bookmarks") {
        return Response.json(
          url.searchParams.get("cursor")
            ? { items: [second] }
            : { items: [first], nextCursor: "next-page" },
        );
      }
      if (
        request.method === "POST" &&
        url.pathname === `/api/v1/bookmarks/${first.id}/reports`
      ) {
        submittedBody = await request.clone().json();
        return problem(409, "Already open");
      }
      return new Response(null, { status: 404 });
    });
    const toast = vi.fn();
    render(PublicFeedPage, { toast });
    expect(await screen.findByText("Svelte guide")).toBeTruthy();
    await fireEvent.click(screen.getByRole("button", { name: "Load more" }));
    expect(await screen.findByText("Svelte testing")).toBeTruthy();
    await fireEvent.click(
      screen.getAllByRole("button", { name: "Report" })[0] as HTMLElement,
    );
    await fireEvent.change(screen.getByLabelText("Reason"), {
      target: { value: "offensive" },
    });
    await fireEvent.input(screen.getByLabelText("Comment"), {
      target: { value: "Needs review" },
    });
    await fireEvent.submit(
      screen.getByRole("dialog").querySelector("form") as HTMLFormElement,
    );

    expect(
      await screen.findByRole("button", { name: "Reported" }),
    ).toBeTruthy();
    expect(submittedBody).toEqual({
      reason: "offensive",
      comment: "Needs review",
    });
    expect(toast).toHaveBeenCalledWith("Already reported");
    expect(
      requests.some(
        (request) =>
          new URL(request.url).searchParams.get("cursor") === "next-page",
      ),
    ).toBe(true);
  });

  it("keeps reporting unavailable for anonymous public-feed readers", async () => {
    setIdentity(null);
    stubFetch(() => Response.json({ items: [bookmark()] }));
    render(PublicFeedPage, { toast: vi.fn() });
    expect(await screen.findByText("Svelte guide")).toBeTruthy();
    await waitFor(() =>
      expect(screen.queryByRole("button", { name: "Report" })).toBeNull(),
    );
  });
});

function withinDialogLabel(dialog: HTMLElement, label: string): HTMLElement {
  const labels = Array.from(dialog.querySelectorAll("label"));
  const match = labels.find((candidate) =>
    candidate.textContent?.startsWith(label),
  );
  const control = match?.querySelector("input, textarea, select");
  if (!(control instanceof HTMLElement))
    throw new Error(`Missing ${label} control`);
  return control;
}
